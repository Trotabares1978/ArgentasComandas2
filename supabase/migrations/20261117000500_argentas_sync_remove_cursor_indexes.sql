-- The receipt protocol no longer sorts or filters by created_at.
-- Remove cursor-era indexes that the staging advisor confirmed are unused.
drop index if exists public.argentas_sync_messages_created_idx;
drop index if exists public.argentas_sync_messages_created_id_idx;
