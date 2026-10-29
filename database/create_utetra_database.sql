USE master;
GO

IF DB_ID(N'UTETraDB') IS NULL
BEGIN
    CREATE DATABASE UTETraDB COLLATE Vietnamese_CI_AS;
END;
GO

ALTER DATABASE UTETraDB SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
GO

USE UTETraDB;
GO

SELECT DB_NAME() AS current_database,
       DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS collation;
GO

-- Các bảng, khóa ngoại và index được Spring Data JPA/Hibernate tạo ở lần chạy đầu.
