-- Settings shared by every node of the 'logcloud' recovery domain, and the
-- initial (expired) leader lease. ON CONFLICT: every node runs this at startup.
INSERT INTO LOG_CLOUD_STARTUP_CONFIG (OWNING_RECOVERY_DOMAIN_NAME, PROPERTY_NAME, PROPERTY_VALUE)
	VALUES ('logcloud', 'com.atomikos.icatch.max_timeout', '300000') ON CONFLICT DO NOTHING;
INSERT INTO LEADER (OWNING_RECOVERY_DOMAIN_NAME)
	SELECT 'logcloud' WHERE NOT EXISTS (SELECT 1 FROM LEADER WHERE OWNING_RECOVERY_DOMAIN_NAME = 'logcloud');
