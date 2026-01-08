-- System Configuration Table
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) UNIQUE NOT NULL COMMENT 'Configuration key',
    config_value VARCHAR(500) NOT NULL COMMENT 'Configuration value',
    description VARCHAR(255) COMMENT 'Configuration description',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT 'System configuration table';

-- Insert default configuration
INSERT INTO sys_config (config_key, config_value, description) 
VALUES ('resource.audit.enabled', 'false', 'Enable resource submission audit (true/false)')
ON DUPLICATE KEY UPDATE config_value = config_value;
