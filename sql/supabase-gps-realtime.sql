-- Habilita cambios en tiempo real para que el dashboard reciba actualizaciones
-- de la tabla public.clientes (incluye gps_track_<sucursal> y gps_public_<sucursal>).
-- Ejecutar una vez en Supabase SQL Editor con un rol que pueda modificar publicaciones.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime'
      AND schemaname = 'public'
      AND tablename = 'clientes'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.clientes;
  END IF;
END $$;
