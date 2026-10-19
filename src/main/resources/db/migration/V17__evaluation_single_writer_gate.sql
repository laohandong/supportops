-- 冻结与切换由数据库门禁约束旧版应用；共享锁与冻结事务排他锁保证单写。
DELIMITER $$
CREATE TRIGGER eval_freeze_0_insert BEFORE INSERT ON evaluation_datasets
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_0_update BEFORE UPDATE ON evaluation_datasets
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_0_delete BEFORE DELETE ON evaluation_datasets
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_1_insert BEFORE INSERT ON evaluation_case_entries
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_1_update BEFORE UPDATE ON evaluation_case_entries
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_1_delete BEFORE DELETE ON evaluation_case_entries
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_2_insert BEFORE INSERT ON evaluation_case_revisions
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_2_update BEFORE UPDATE ON evaluation_case_revisions
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_2_delete BEFORE DELETE ON evaluation_case_revisions
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_3_insert BEFORE INSERT ON evaluation_cases
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_3_update BEFORE UPDATE ON evaluation_cases
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_3_delete BEFORE DELETE ON evaluation_cases
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_4_insert BEFORE INSERT ON evaluation_experiments
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_4_update BEFORE UPDATE ON evaluation_experiments
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_4_delete BEFORE DELETE ON evaluation_experiments
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_5_insert BEFORE INSERT ON evaluation_attempts
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_5_update BEFORE UPDATE ON evaluation_attempts
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_5_delete BEFORE DELETE ON evaluation_attempts
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_6_insert BEFORE INSERT ON evaluation_groups
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_6_update BEFORE UPDATE ON evaluation_groups
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_6_delete BEFORE DELETE ON evaluation_groups
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_7_insert BEFORE INSERT ON evaluation_samples
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_7_update BEFORE UPDATE ON evaluation_samples
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_7_delete BEFORE DELETE ON evaluation_samples
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_8_insert BEFORE INSERT ON evaluation_evidence
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_8_update BEFORE UPDATE ON evaluation_evidence
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_8_delete BEFORE DELETE ON evaluation_evidence
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_9_insert BEFORE INSERT ON evaluation_reviews
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_9_update BEFORE UPDATE ON evaluation_reviews
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_9_delete BEFORE DELETE ON evaluation_reviews
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_10_insert BEFORE INSERT ON evaluation_events
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_10_update BEFORE UPDATE ON evaluation_events
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_10_delete BEFORE DELETE ON evaluation_events
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_11_insert BEFORE INSERT ON evaluation_case_events
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_11_update BEFORE UPDATE ON evaluation_case_events
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_11_delete BEFORE DELETE ON evaluation_case_events
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_12_insert BEFORE INSERT ON evaluation_library_requests
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_12_update BEFORE UPDATE ON evaluation_library_requests
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_12_delete BEFORE DELETE ON evaluation_library_requests
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_13_insert BEFORE INSERT ON evaluation_runner_guard
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_13_update BEFORE UPDATE ON evaluation_runner_guard
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER eval_freeze_13_delete BEFORE DELETE ON evaluation_runner_guard
FOR EACH ROW
BEGIN
  DECLARE migration_state VARCHAR(32);
  SELECT state INTO migration_state FROM evaluation_migration_control WHERE id=1 FOR SHARE;
  IF migration_state <> 'ACTIVE' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'EVALUATION_MIGRATION_READ_ONLY';
  END IF;
END$$
DELIMITER ;

