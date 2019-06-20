INSERT INTO LOG_CLOUD_STARTUP_CONFIG (PROPERTY_NAME, PROPERTY_VALUE) values ('com.atomikos.icatch.max_timeout', '300000');
INSERT INTO LOG_CLOUD_STARTUP_CONFIG (PROPERTY_NAME, PROPERTY_VALUE) values ('com.atomikos.icatch.tm_unique_name', 'logcloud');
INSERT INTO LEADER (ACTIVE_JVM_ID) select ('NONE') WHERE NOT EXISTS (select 1 from LEADER); -- EXPIRY defaults to 0
