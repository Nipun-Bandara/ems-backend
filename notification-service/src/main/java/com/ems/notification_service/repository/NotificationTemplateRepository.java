package com.ems.notification_service.repository;

import com.ems.notification_service.entity.NotificationTemplate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByTemplateKey(String templateKey);
}
