-- System Configuration Table
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) UNIQUE NOT NULL COMMENT 'Configuration key',
    config_value VARCHAR(500) NOT NULL COMMENT 'Configuration value',
    description VARCHAR(255) COMMENT 'Configuration description',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT 'System configuration table';

-- Insert default configurations
INSERT INTO sys_config (config_key, config_value, description) VALUES
('resource.audit.enabled', 'true', 'Enable resource submission audit (true/false)'),
('resource.max.per.user', '100', 'Maximum resources per user'),
('resource.submit.interval.seconds', '60', 'Minimum seconds between resource submissions')
ON DUPLICATE KEY UPDATE config_value = config_value;
