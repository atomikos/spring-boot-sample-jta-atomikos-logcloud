CREATE TABLE COORDINATOR (
			ID VARCHAR(36) NOT NULL, 
			STATE VARCHAR(36) NOT NULL, 
			EXPIRES BIGINT NOT NULL, 
			SUPERIOR_ID VARCHAR(64),
			RECOVERY_DOMAIN_NAME VARCHAR(45) NOT NULL);
				
CREATE TABLE LOG_CLOUD_STARTUP_CONFIG (
			PROPERTY_NAME VARCHAR(2000) NOT NULL,
			PROPERTY_VALUE VARCHAR(2000) NOT NULL, 
			CONSTRAINT LOG_CLOUD_STARTUP_CONFIG_PK PRIMARY KEY (PROPERTY_NAME));

CREATE TABLE LEADER (
			ACTIVE_JVM_ID VARCHAR(2000) NOT NULL,
			EXPIRY BIGINT NOT NULL default 0,
			CONSTRAINT ACTIVE_JVM_ID_PK PRIMARY KEY (ACTIVE_JVM_ID));
					
CREATE TABLE REMOTE_PARTICIPANT (
			COORDINATOR_ID VARCHAR(36) NOT NULL, 
			REMOTE_PARTICIPANT_URI VARCHAR(64) NOT NULL);


