package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL + pgvector implementation of the video vector store.
 */
@Service
public class PgVectorVideoStore implements VideoVectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorVideoStore.class);
    private static final String DEFAULT_TABLE = "video_chunk_vectors";

    private final HikariDataSource dataSource;
    private final boolean enabled;
    private final int vectorDimension;
    private final String table;
    private final AtomicBoolean schemaReady = new AtomicBoolean();

    public PgVectorVideoStore(
            @Value("${vector.pg.enabled:true}") boolean enabled,
            @Value("${vector.pg.url}") String jdbcUrl,
            @Value("${vector.pg.username}") String username,
            @Value("${vector.pg.password}") String password,
            @Value("${vector.pg.dimension:1024}") int vectorDimension,
            @Value("${vector.pg.table:" + DEFAULT_TABLE + "}") String table,
            @Value("${vector.pg.pool.maximum-size:4}") int maximumPoolSize,
            @Value("${vector.pg.pool.minimum-idle:1}") int minimumIdle) {
        if (vectorDimension <= 0 || vectorDimension > 2_000) {
            throw new IllegalArgumentException("pgvector dimension must be between 1 and 2000");
        }
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("pgvector table name is invalid");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("vidotrace-pgvector");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(3_000);
        config.setValidationTimeout(2_000);
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
        this.enabled = enabled;
        this.vectorDimension = vectorDimension;
        this.table = table;
    }

    @PreDestroy
    public void close() {
        dataSource.close();
    }

    @Override
    public void upsert(Long mediaId, List<VideoChunk> chunks) {
        if (!enabled || mediaId == null || chunks == null || chunks.isEmpty()) return;
        List<VideoChunk> vectorized = chunks.stream()
                .filter(chunk -> !chunk.embedding().isEmpty())
                .toList();
        if (vectorized.isEmpty()) return;
        vectorized.forEach(chunk -> validateDimension(chunk.embedding()));

        try {
            ensureSchema();
            String sql = """
                    INSERT INTO %s (media_id, start_ms, end_ms, embedding)
                    VALUES (?, ?, ?, ?::vector)
                    ON CONFLICT (media_id, start_ms, end_ms)
                    DO UPDATE SET embedding = EXCLUDED.embedding
                    """.formatted(quotedTable());
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                for (VideoChunk chunk : vectorized) {
                    statement.setLong(1, mediaId);
                    statement.setLong(2, chunk.startTime());
                    statement.setLong(3, chunk.endTime());
                    statement.setString(4, vectorLiteral(chunk.embedding()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        } catch (SQLException e) {
            schemaReady.set(false);
            throw new IllegalStateException("pgvector 分段向量写入失败", e);
        }
    }

    @Override
    public List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit) {
        if (!enabled || mediaId == null || queryEmbedding == null
                || queryEmbedding.isEmpty() || limit <= 0) {
            return List.of();
        }
        validateDimension(queryEmbedding);

        try {
            ensureSchema();
            String sql = """
                    SELECT start_ms,
                           end_ms,
                           1 - (embedding <=> ?::vector) AS score
                    FROM %s
                    WHERE media_id = ?
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """.formatted(quotedTable());
            String vector = vectorLiteral(queryEmbedding);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, vector);
                statement.setLong(2, mediaId);
                statement.setString(3, vector);
                statement.setInt(4, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<VectorHit> hits = new ArrayList<>();
                    while (resultSet.next()) {
                        hits.add(new VectorHit(
                                resultSet.getLong("start_ms"),
                                resultSet.getLong("end_ms"),
                                resultSet.getDouble("score")));
                    }
                    return hits;
                }
            }
        } catch (SQLException e) {
            schemaReady.set(false);
            throw new IllegalStateException("pgvector 语义检索失败", e);
        }
    }

    @Override
    public void deleteMedia(Long mediaId) {
        if (!enabled || mediaId == null) return;
        try {
            ensureSchema();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM " + quotedTable() + " WHERE media_id = ?")) {
                statement.setLong(1, mediaId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("pgvector_media_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    private void ensureSchema() throws SQLException {
        if (schemaReady.get()) return;
        synchronized (schemaReady) {
            if (schemaReady.get()) return;
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS %s (
                            media_id BIGINT NOT NULL,
                            start_ms BIGINT NOT NULL,
                            end_ms BIGINT NOT NULL,
                            embedding vector(%d) NOT NULL,
                            PRIMARY KEY (media_id, start_ms, end_ms)
                        )
                        """.formatted(quotedTable(), vectorDimension));
                validateStoredDimension(connection);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS %s
                        ON %s (media_id)
                        """.formatted(quotedIdentifier(table + "_media_idx"), quotedTable()));
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS %s
                        ON %s USING hnsw (embedding vector_cosine_ops)
                        """.formatted(quotedIdentifier(table + "_embedding_hnsw_idx"), quotedTable()));
                schemaReady.set(true);
            }
        }
    }

    private void validateStoredDimension(Connection connection) throws SQLException {
        String sql = """
                SELECT a.atttypmod
                FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                WHERE c.relname = ? AND a.attname = 'embedding' AND a.attnum > 0
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != vectorDimension) {
                    throw new IllegalStateException(
                            "pgvector 表维度与 VECTOR_DIMENSION 不一致，需要受控重建索引");
                }
            }
        }
    }

    private void validateDimension(List<Double> vector) {
        if (vector.size() != vectorDimension) {
            throw new IllegalArgumentException(
                    "Embedding dimension " + vector.size()
                            + " does not match configured dimension " + vectorDimension);
        }
    }

    private String vectorLiteral(List<Double> vector) {
        StringBuilder result = new StringBuilder(vector.size() * 12).append('[');
        for (int i = 0; i < vector.size(); i++) {
            Double value = vector.get(i);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains a non-finite value");
            }
            if (i > 0) result.append(',');
            result.append(value);
        }
        return result.append(']').toString();
    }

    private String quotedTable() {
        return quotedIdentifier(table);
    }

    private String quotedIdentifier(String identifier) {
        return '"' + identifier + '"';
    }
}
