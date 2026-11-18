-- The official image creates bite_wear and bite using environment variables.
ALTER DATABASE `bite_wear` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Match the course's replication and binlog-reading privileges.
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'bite'@'%';
