-- Retain the latest tool observation when a task is reloaded between turns.
ALTER TABLE agent_task ADD COLUMN last_tool_result JSON NULL AFTER collected_facts;
