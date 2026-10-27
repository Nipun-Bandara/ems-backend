-- Opt-in development fixture. Synthetic date; NOT a production holiday calendar.
INSERT INTO organization_holiday(date,name) VALUES ('2099-01-05','Synthetic development closure')
ON CONFLICT (date) DO NOTHING;
