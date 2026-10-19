-- ══════════════════════════════════════════════════════════════════════
-- Habilitar fotos e imágenes de ejemplo (renders) en el agente de ventas
--
-- SQL puro: corre en IntelliJ/DataGrip sin pedir parámetros.
-- Se puede correr varias veces (no duplica nada).
--
-- Antes: arrancar agents-services una vez para que Liquibase aplique
-- 07 (supports_vision), 09 (image_cost, image_model_id, render_jobs) y
-- 11 (generates_images).
-- Después: reiniciar agents-services o esperar 5 min (caché del contexto).
-- ══════════════════════════════════════════════════════════════════════

SET search_path TO agents_dev;   -- esquema de agents-services en dev

DO $renders$
DECLARE
    -- ── DATOS ───────────────────────────────────────────────────────────
    -- Empresa (tenant_id = id de la empresa en el ERP). NULL = todas las
    -- empresas que ya tienen playbook propio.
    v_tenant      bigint  := NULL;
    v_renders_max int     := 3;      -- imágenes de ejemplo por cotización
    v_fotos_turno int     := 3;      -- fotos que lee por mensaje
    -- US$ por imagen. gpt-image-1, calidad media: ~0.042 (1024x1024) a
    -- ~0.063 (1536x1024). Se usa para Uso y costos.
    v_image_cost  numeric := 0.042;
    -- ────────────────────────────────────────────────────────────────────
    v_img  bigint;
    v_pbs  int;
BEGIN
    -- 1. El modelo de chat del agente lee fotos.
    UPDATE ai_models SET supports_vision = TRUE
    WHERE model_name IN ('gpt-4o', 'gpt-4o-mini');

    -- 2. Modelo de imágenes (lo usa /v1/images/edits).
    SELECT id INTO v_img FROM ai_models WHERE model_name = 'gpt-image-1';
    IF v_img IS NULL THEN
        INSERT INTO ai_models (provider_id, model_name, context_window, input_cost_1k, output_cost_1k,
                               supports_tools, supports_cache, supports_vision, image_cost, generates_images)
        SELECT p.id, 'gpt-image-1', 32000, 0.005, 0.04, FALSE, FALSE, TRUE, v_image_cost, TRUE
        FROM ai_providers p WHERE p.code = 'openai'
        RETURNING id INTO v_img;
        IF v_img IS NULL THEN
            RAISE EXCEPTION 'No existe el proveedor openai en ai_providers';
        END IF;
    ELSE
        UPDATE ai_models SET image_cost = v_image_cost, generates_images = TRUE, is_active = TRUE WHERE id = v_img;
    END IF;

    -- 3. El agente de ventas usa ese modelo para los renders.
    UPDATE ai_agents SET image_model_id = v_img WHERE code = 'ventas';

    -- 4. Playbook: acepta fotos y genera renders.
    UPDATE agent_playbooks
    SET config = jsonb_set(config, '{imagenes}',
            COALESCE(config->'imagenes', '{}'::jsonb) || jsonb_build_object(
                'aceptar', TRUE, 'max_por_turno', v_fotos_turno,
                'generar_renders', TRUE, 'renders_max', v_renders_max), TRUE)
    WHERE agent_code = 'ventas' AND is_active AND tenant_id IS NOT NULL
      AND (v_tenant IS NULL OR tenant_id = v_tenant);
    GET DIAGNOSTICS v_pbs = ROW_COUNT;

    IF v_pbs = 0 THEN
        RAISE NOTICE 'Ningún playbook de empresa actualizado: la empresa no tiene playbook propio (crea uno desde la consola o con un INSERT en agent_playbooks).';
    ELSE
        RAISE NOTICE 'Modelo de imágenes id %, % playbook(s) actualizados', v_img, v_pbs;
    END IF;
END
$renders$;

-- Verificación
SELECT a.code, m.model_name AS modelo, m.supports_vision AS lee_fotos, im.model_name AS modelo_imagenes, im.image_cost
FROM ai_agents a
JOIN ai_models m ON m.id = a.model_id
LEFT JOIN ai_models im ON im.id = a.image_model_id
WHERE a.code = 'ventas';

SELECT tenant_id, version, config->'imagenes' AS imagenes
FROM agent_playbooks
WHERE agent_code = 'ventas' AND is_active;
