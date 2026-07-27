package com.example.server.service;

import com.example.server.dto.VideoChunk;

import java.util.List;

/**
 * 视频分段向量存储边界。业务层只依赖这个接口，避免绑定具体向量数据库。
 */
public interface VideoVectorStore {

    void upsert(Long mediaId, List<VideoChunk> chunks);

    List<VectorHit> search(Long mediaId, List<Double> queryEmbedding, int limit);

    void deleteMedia(Long mediaId);

    record VectorHit(long startMs, long endMs, double score) {
    }
}
