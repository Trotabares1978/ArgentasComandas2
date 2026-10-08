-- Keep the client roles on the minimum privileges required by Argentas online synchronization.
REVOKE REFERENCES, TRIGGER, TRUNCATE ON TABLE public.argentas_conexion FROM anon, authenticated;
