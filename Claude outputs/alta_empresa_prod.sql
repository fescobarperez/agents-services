-- ══════════════════════════════════════════════════════════════════════
-- Alta de empresa + usuario administrador (producción)
--
-- SQL puro (sin comandos de psql): corre igual en IntelliJ/DataGrip, pgAdmin
-- o psql. No pide parámetros. Ejecutar el archivo completo.
--
-- Todo va en un solo bloque DO: si algo falla, no queda nada a medias.
-- Establecimiento SAT y sucursales los ingresa el cliente desde el sistema.
--
-- Además de empresa, rol y usuario, crea la configuración interna que las
-- migraciones 035–065 solo sembraron para las empresas que ya existían
-- (niveles y reglas de autorización, IVA, cuentas de posteo, correlativos,
-- categorías de cargo). El cliente no puede ingresarla desde ninguna pantalla.
-- ══════════════════════════════════════════════════════════════════════

SET search_path TO stackline_prod;   -- confirmar esquema de producción

DO $alta$
DECLARE
    -- ── DATOS ───────────────────────────────────────────────────────────
    v_codigo       text := 'MADERAVIVA';
    v_nombre       text := 'Madera Viva SA';
    v_nit          text := NULL;            -- NIT por confirmar
    v_admin_nombre text := 'Administrador';
    v_admin_email  text := 'admin@maderaviva.com';
    -- Hash bcrypt (coste 12) de la contraseña; nunca la contraseña en claro
    v_admin_hash   text := '<HASH_BCRYPT>';
    -- ────────────────────────────────────────────────────────────────────
    v_cid bigint;
    v_rid bigint;
BEGIN
    -- Empresa
    INSERT INTO companies (code, name, nit, plan, status, base_currency)
    VALUES (upper(v_codigo), v_nombre, v_nit, 'standard', 'active', 'GTQ')
    RETURNING id INTO v_cid;

    -- Niveles de autorización (035)
    INSERT INTO authorization_levels (company_id, code, name, rank)
    SELECT v_cid, v.code, v.name, v.rank
    FROM (VALUES ('operativo','Operativo',0), ('supervisor','Supervisor',1),
                 ('gerente','Gerente',2), ('direccion','Dirección',3)) AS v(code, name, rank);

    -- Rol Administrador con todos los permisos
    INSERT INTO roles (company_id, name, description, permissions)
    VALUES (v_cid, 'Administrador', 'Acceso total. Creado al dar de alta la empresa.', '["*"]'::jsonb)
    RETURNING id INTO v_rid;

    -- Usuario administrador (nivel Dirección)
    INSERT INTO users (company_id, role_id, name, email, password_hash, status, auth_level_id)
    SELECT v_cid, v_rid, v_admin_nombre, lower(trim(v_admin_email)), v_admin_hash, 'active', l.id
    FROM authorization_levels l
    WHERE l.company_id = v_cid AND l.code = 'direccion';

    -- Tipos y reglas de autorización (036, 043, 048)
    INSERT INTO authorization_types (company_id, code, name, description, resolution_mode) VALUES
     (v_cid, 'pos_discount', 'Descuento manual en POS',
      'Descuento de ingreso libre en el punto de venta. Los descuentos de promociones configuradas no requieren autorización.', 'both'),
     (v_cid, 'project_overrun', 'Sobrecosto de proyecto',
      'Un gasto que llevaría el costo del proyecto por encima de lo contratado con el cliente.', 'both'),
     (v_cid, 'project_overbill', 'Facturación sobre contrato',
      'Emitir un documento que lleva lo facturado por encima del monto contratado del proyecto.', 'both');

    INSERT INTO authorization_rules (company_id, type_id, unit, min_value, max_value, level_id)
    SELECT v_cid, t.id, 'percent', r.min_v, r.max_v, l.id
    FROM (VALUES ('pos_discount',     10::numeric, 30::numeric, 'supervisor'),
                 ('pos_discount',     30,          NULL,        'gerente'),
                 ('project_overrun',  90,          100,         'supervisor'),
                 ('project_overrun',  100,         NULL,        'gerente'),
                 ('project_overbill', 100,         NULL,        'gerente')) AS r(tipo, min_v, max_v, nivel)
    JOIN authorization_types  t ON t.company_id = v_cid AND t.code = r.tipo
    JOIN authorization_levels l ON l.company_id = v_cid AND l.code = r.nivel;

    -- IVA (038)
    INSERT INTO company_settings (company_id, setting_key, setting_value, category)
    VALUES (v_cid, 'tax.iva_rate', '12', 'taxes');

    -- Cuentas de posteo y su mapeo (053, 054, 058)
    INSERT INTO accounts (company_id, code, name, level, normal_balance, allows_entries)
    SELECT v_cid, v.code, v.name, 4, v.saldo, TRUE
    FROM (VALUES
        ('110101', 'Caja',                     'debit'),
        ('110102', 'Bancos',                   'debit'),
        ('110201', 'Clientes',                 'debit'),
        ('110301', 'Inventario de mercaderia', 'debit'),
        ('110401', 'IVA credito fiscal',       'debit'),
        ('410101', 'Ingresos por ventas',      'credit'),
        ('210101', 'Proveedores',              'credit'),
        ('210201', 'IVA debito fiscal',        'credit'),
        ('210301', 'Anticipos de clientes',    'credit'),
        ('510101', 'Costo de ventas',          'debit')
    ) AS v(code, name, saldo);

    INSERT INTO company_settings (company_id, setting_key, setting_value)
    SELECT v_cid, m.clave, a.id::text
    FROM accounts a
    JOIN (VALUES
        ('110101', 'posting.cash'),
        ('110102', 'posting.bank'),
        ('110201', 'posting.receivable'),
        ('410101', 'posting.revenue'),
        ('210201', 'posting.tax_payable'),
        ('110401', 'posting.tax_credit'),
        ('110301', 'posting.inventory'),
        ('510101', 'posting.cost_of_sales'),
        ('210101', 'posting.payable'),
        ('210301', 'posting.customer_advance')
    ) AS m(code, clave) ON m.code = a.code
    WHERE a.company_id = v_cid;

    -- Correlativos (050, 063)
    INSERT INTO document_sequences (company_id, doc_type, series, prefix, padding, next_number) VALUES
     (v_cid, 'RECIBO', 'A', 'REC-', 6, 1),
     (v_cid, 'FACT',   'A', 'FAC-', 6, 1),
     (v_cid, 'COT',    'A', 'COT-', 6, 1),
     (v_cid, 'RFQ',    'A', 'RFQ-', 6, 1),
     (v_cid, 'PRY',    'A', 'PRY-', 4, 1);

    -- Categorías de cargos de cotización (065)
    INSERT INTO charge_categories (company_id, code, name, is_operating, protected, sort_order) VALUES
     (v_cid, 'OPERATIVO',  'Gastos operativos', TRUE,  TRUE,  0),
     (v_cid, 'MANO_OBRA',  'Mano de obra',      FALSE, FALSE, 10),
     (v_cid, 'TRANSPORTE', 'Transporte',        FALSE, FALSE, 20),
     (v_cid, 'OTRO',       'Otros cargos',      FALSE, FALSE, 90);

    RAISE NOTICE 'Empresa % creada con id %', upper(v_codigo), v_cid;
END
$alta$;

-- Verificación
SELECT c.id, c.code, c.name, c.nit, u.email AS admin, r.name AS rol, u.status
FROM companies c
JOIN users u ON u.company_id = c.id
JOIN roles r ON r.id = u.role_id
WHERE c.code = 'MADERAVIVA';
