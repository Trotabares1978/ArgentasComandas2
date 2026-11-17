-- Harden SECURITY DEFINER RPC resolution against search_path shadowing.
alter function public.argentas_sync_pending_messages(text, integer) set search_path = '';
alter function public.argentas_sync_ack_messages(text, bigint[]) set search_path = '';
