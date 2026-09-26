package com.erp_maya.agent.context.service

import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.domain.ToolMode
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import io.micronaut.jdbc.DataSourceResolver
import javax.sql.DataSource

/**
 * Resolucion del contexto contra un Postgres real con el esquema y los datos
 * semilla que aplica Liquibase. Lo que se prueba no es el SQL por el SQL, sino
 * que el turno falle CERRADO en cada una de las formas en que puede faltar
 * configuracion.
 */
@MicronautTest(transactional = false)
class ExecutionContextResolverTest {

    @Inject
    lateinit var resolver: ExecutionContextResolver

    // El DataSource que inyecta Micronaut Data es contextual: exige una
    // conexion ya abierta por @Transactional. Las fixtures corren fuera de toda
    // transaccion, asi que se desenvuelve para hablar con el pool directo.
    @Inject
    lateinit var dataSource: DataSource

    // DelegatingDataSource desaparecio en Micronaut SQL 7; el resolver es su
    // reemplazo para llegar al DataSource real detras del contextual.
    @Inject
    lateinit var dataSourceResolver: DataSourceResolver

    private val creados = mutableListOf<String>()

    @AfterEach
    fun limpiar() {
        creados.forEach { sql -> ejecutar(sql) }
        creados.clear()
    }

    @Test
    fun `resuelve el canal semilla con todo lo que el turno necesita`() {
        val ctx = resolver.resolve("wa:pruebas", null)

        assertEquals(1L, ctx.tenantId)
        assertEquals("whatsapp", ctx.channel.kind)
        assertEquals("ventas", ctx.agent.code)
        assertEquals("gpt-4o", ctx.model.modelName)
        assertEquals("openai", ctx.model.providerCode)
        assertEquals("gpt-4o-mini", ctx.fallbackModel?.modelName)
        assertEquals(1, ctx.prompt.version)
        assertTrue(ctx.prompt.body.isNotBlank())
        assertEquals(BigDecimal("25000.00"), ctx.policy.quoteLimit)
        assertEquals("es-GT", ctx.policy.locale)
    }

    @Test
    fun `las capacidades del canal se leen del jsonb`() {
        val ctx = resolver.resolve("wa:pruebas", null)
        assertEquals(3, ctx.channel.capabilities.buttons)
        assertEquals(4096, ctx.channel.capabilities.maxChars)
        assertEquals(false, ctx.channel.capabilities.markdown)
    }

    @Test
    fun `las herramientas traen su modo y su tope`() {
        val ctx = resolver.resolve("wa:pruebas", null)
        assertEquals(6, ctx.tools.size)

        val emitir = requireNotNull(ctx.toolFor("cotizaciones.issue"))
        assertEquals(ToolMode.WRITE, emitir.mode)
        assertEquals(false, emitir.autoApprove)

        val buscar = requireNotNull(ctx.toolFor("productos.search"))
        assertEquals(ToolMode.READ, buscar.mode)
        assertTrue(buscar.autoApprove)
    }

    @Test
    fun `un canal desconocido no resuelve nada`() {
        val e = assertThrows(AgentUnavailableException::class.java) {
            resolver.resolve("wa:no-existe", null)
        }
        assertEquals(AgentUnavailableException.Reason.NO_ACTIVE_AGENT, e.reason)
    }

    @Test
    fun `una asignacion vencida no atiende`() {
        // Canal nuevo cuya unica asignacion caduco ayer.
        ejecutar(
            """
            INSERT INTO channels (kind, account_ref, capabilities)
            VALUES ('whatsapp', 'wa:vencido', '{"buttons":3}');
            INSERT INTO channel_agents (channel_id, tenant_id, agent_id, valid_from, valid_to, priority)
            SELECT c.id, 1, a.id, now() - interval '10 days', now() - interval '1 day', 10
            FROM channels c, ai_agents a WHERE c.account_ref = 'wa:vencido' AND a.code = 'ventas';
            """,
        )
        creados += "DELETE FROM channel_agents WHERE channel_id IN (SELECT id FROM channels WHERE account_ref = 'wa:vencido'); DELETE FROM channels WHERE account_ref = 'wa:vencido';"

        val e = assertThrows(AgentUnavailableException::class.java) {
            resolver.resolve("wa:vencido", null)
        }
        assertEquals(AgentUnavailableException.Reason.NO_ACTIVE_AGENT, e.reason)
    }

    @Test
    fun `una empresa con el bot apagado no atiende`() {
        ejecutar(
            """
            INSERT INTO channels (kind, account_ref, capabilities)
            VALUES ('whatsapp', 'wa:apagado', '{"buttons":3}');
            INSERT INTO agent_tenant_policies (tenant_id, is_enabled) VALUES (900, false);
            INSERT INTO channel_agents (channel_id, tenant_id, agent_id, priority)
            SELECT c.id, 900, a.id, 10 FROM channels c, ai_agents a
            WHERE c.account_ref = 'wa:apagado' AND a.code = 'ventas';
            """,
        )
        creados += "DELETE FROM channel_agents WHERE tenant_id = 900; DELETE FROM agent_tenant_policies WHERE tenant_id = 900; DELETE FROM channels WHERE account_ref = 'wa:apagado';"

        val e = assertThrows(AgentUnavailableException::class.java) {
            resolver.resolve("wa:apagado", null)
        }
        assertEquals(AgentUnavailableException.Reason.TENANT_DISABLED, e.reason)
    }

    @Test
    fun `una empresa sin fila de politica tampoco atiende`() {
        // Omitir el alta no puede equivaler a autorizarla.
        ejecutar(
            """
            INSERT INTO channels (kind, account_ref, capabilities)
            VALUES ('whatsapp', 'wa:sin-politica', '{"buttons":3}');
            INSERT INTO channel_agents (channel_id, tenant_id, agent_id, priority)
            SELECT c.id, 901, a.id, 10 FROM channels c, ai_agents a
            WHERE c.account_ref = 'wa:sin-politica' AND a.code = 'ventas';
            """,
        )
        creados += "DELETE FROM channel_agents WHERE tenant_id = 901; DELETE FROM channels WHERE account_ref = 'wa:sin-politica';"

        val e = assertThrows(AgentUnavailableException::class.java) {
            resolver.resolve("wa:sin-politica", null)
        }
        assertEquals(AgentUnavailableException.Reason.TENANT_DISABLED, e.reason)
    }

    @Test
    fun `un canal compartido por dos empresas es ambiguo, no se adivina`() {
        ejecutar(
            """
            INSERT INTO channels (kind, account_ref, capabilities)
            VALUES ('whatsapp', 'wa:compartido', '{"buttons":3}');
            INSERT INTO agent_tenant_policies (tenant_id, is_enabled) VALUES (910, true), (911, true);
            INSERT INTO channel_agents (channel_id, tenant_id, agent_id, priority)
            SELECT c.id, 910, a.id, 50 FROM channels c, ai_agents a
            WHERE c.account_ref = 'wa:compartido' AND a.code = 'ventas';
            INSERT INTO channel_agents (channel_id, tenant_id, agent_id, priority)
            SELECT c.id, 911, a.id, 10 FROM channels c, ai_agents a
            WHERE c.account_ref = 'wa:compartido' AND a.code = 'ventas';
            """,
        )
        creados += "DELETE FROM channel_agents WHERE tenant_id IN (910, 911); DELETE FROM agent_tenant_policies WHERE tenant_id IN (910, 911); DELETE FROM channels WHERE account_ref = 'wa:compartido';"

        val e = assertThrows(AgentUnavailableException::class.java) {
            resolver.resolve("wa:compartido", null)
        }
        assertEquals(AgentUnavailableException.Reason.AMBIGUOUS_TENANT, e.reason)

        // Con la empresa declarada —el widget del ERP la saca del JWT— deja de
        // ser ambiguo y resuelve la que corresponde, no la de mayor prioridad.
        assertEquals(911L, resolver.resolve("wa:compartido", 911L).tenantId)
    }

    private fun ejecutar(sql: String) {
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { it.execute(sql) }
        }
    }
}
