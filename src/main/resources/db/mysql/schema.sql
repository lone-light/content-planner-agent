CREATE TABLE IF NOT EXISTS agent_task (
    id VARCHAR(128) NOT NULL PRIMARY KEY,
    model_mode VARCHAR(32) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    state_json LONGTEXT NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_task_request (model_mode, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS saved_plan (
    id VARCHAR(80) NOT NULL PRIMARY KEY,
    model_mode VARCHAR(32) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    plan_id VARCHAR(80) NOT NULL,
    plan_json LONGTEXT NOT NULL,
    save_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_saved_request (model_mode, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
