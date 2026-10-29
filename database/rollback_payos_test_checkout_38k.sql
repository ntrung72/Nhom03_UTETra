SET XACT_ABORT ON;
IF DB_NAME() <> N'UTETraDB'
    THROW 51000, 'Select UTETraDB before running this script.', 1;

BEGIN TRANSACTION;

UPDATE dbo.carriers
SET shipping_fee = 18000
WHERE name = N'UTETra Express' AND shipping_fee = 1;

IF EXISTS (SELECT 1 FROM dbo.vouchers WHERE code = 'TESTPAYOS38K')
BEGIN
    IF EXISTS (
        SELECT 1 FROM dbo.customer_orders AS o
        JOIN dbo.vouchers AS v ON v.id = o.voucher_id
        WHERE v.code = 'TESTPAYOS38K'
    ) OR EXISTS (
        SELECT 1 FROM dbo.voucher_usages AS u
        JOIN dbo.vouchers AS v ON v.id = u.voucher_id
        WHERE v.code = 'TESTPAYOS38K'
    )
        UPDATE dbo.vouchers SET active = 0 WHERE code = 'TESTPAYOS38K';
    ELSE
        DELETE FROM dbo.vouchers WHERE code = 'TESTPAYOS38K';
END;

COMMIT TRANSACTION;

SELECT name, shipping_fee FROM dbo.carriers WHERE name = N'UTETra Express';
SELECT code, active FROM dbo.vouchers WHERE code = 'TESTPAYOS38K';
