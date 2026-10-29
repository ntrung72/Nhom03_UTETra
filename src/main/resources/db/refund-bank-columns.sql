IF COL_LENGTH('dbo.return_requests', 'refund_bank_name') IS NULL
    ALTER TABLE dbo.return_requests ADD refund_bank_name nvarchar(120) NULL;
IF COL_LENGTH('dbo.return_requests', 'refund_account_name') IS NULL
    ALTER TABLE dbo.return_requests ADD refund_account_name nvarchar(120) NULL;
IF COL_LENGTH('dbo.return_requests', 'refund_account_number') IS NULL
    ALTER TABLE dbo.return_requests ADD refund_account_number nvarchar(24) NULL;
IF COL_LENGTH('dbo.return_requests', 'return_required') IS NULL
    ALTER TABLE dbo.return_requests ADD return_required bit NULL;
