ALTER TABLE app.coding_approval_decision
    DROP CONSTRAINT ck_coding_approval_decision_node,
    DROP CONSTRAINT ck_coding_approval_decision_round;

ALTER TABLE app.coding_approval_decision
    ADD CONSTRAINT ck_coding_approval_decision_node CHECK (
        node_id ~ '^[a-z][a-z0-9_-]{0,63}$'),
    ADD CONSTRAINT ck_coding_approval_decision_round CHECK (
        stage_round >= 1);
