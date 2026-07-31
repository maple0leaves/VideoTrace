package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultMediaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultMediaInitializer.class);

    private final DefaultMediaProvisioningService provisioningService;
    private final UserMapper userMapper;
    private final boolean backfillExistingUsers;

    public DefaultMediaInitializer(
            DefaultMediaProvisioningService provisioningService,
            UserMapper userMapper,
            @Value("${app.default-media.backfill-existing-users:true}")
            boolean backfillExistingUsers) {
        this.provisioningService = provisioningService;
        this.userMapper = userMapper;
        this.backfillExistingUsers = backfillExistingUsers;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!provisioningService.enabled()) return;

        provisioningService.initializeTemplate();
        if (!backfillExistingUsers) return;

        List<User> users = userMapper.selectList(
                new QueryWrapper<User>().select("id").orderByAsc("id"));
        int succeeded = 0;
        int failed = 0;
        for (User user : users) {
            try {
                provisioningService.provisionUser(user.getId());
                succeeded++;
            } catch (RuntimeException error) {
                failed++;
                log.error("default_media_backfill_failed userId={}", user.getId(), error);
            }
        }
        log.info("default_media_backfill_completed succeeded={} failed={}", succeeded, failed);
    }
}
