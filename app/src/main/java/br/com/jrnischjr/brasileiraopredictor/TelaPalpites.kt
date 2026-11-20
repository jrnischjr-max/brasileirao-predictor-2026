package br.com.jrnischjr.brasileiraopredictor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import androidx.compose.ui.Alignment

@Composable
fun TelaPalpites(
    corDestaque: Color,
    corCartao: Color,
    uid: String
) {
    val clipboardManager = LocalClipboardManager.current
    val jogos = remember { mutableStateListOf<JSONObject>() }
    val jogosHistoricos = remember { mutableStateListOf<JSONObject>() }
    val clubes = remember { mutableStateListOf<JSONObject>() }
    val historico = remember { mutableStateListOf<JSONObject>() }

    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf("") }
    var jogosProntos by remember { mutableStateOf(false) }
    var tabelaPronta by remember { mutableStateOf(false) }
    var baseHistoricaPronta by remember { mutableStateOf(false) }
    var erroBaseHistorica by remember { mutableStateOf("") }
    var historicoCarregando by remember { mutableStateOf(false) }
    var erroHistorico by remember { mutableStateOf("") }
    var salvandoPalpite by remember { mutableStateOf(false) }
    var salvandoPalpiteUsuario by remember { mutableStateOf(false) }
    var simulandoHistorico by remember { mutableStateOf(false) }

    var rodadaSelecionada by remember { mutableStateOf(0) }
    var rodadaMenuAberto by remember { mutableStateOf(false) }

    fun carregarHistorico() {
        if (uid.isBlank()) {
            historicoCarregando = false
            erroHistorico = "Faça login para acessar seu histórico."
            return
        }

        historicoCarregando = true
        HistoricoPalpitesNuvem.carregar(uid) { lista, mensagem ->
            historicoCarregando = false
            if (lista != null) {
                historico.clear()
                historico.addAll(lista)
                erroHistorico = ""
            } else {
                erroHistorico = mensagem ?: "Não foi possível carregar o histórico."
            }
        }
    }

    fun atualizarHistorico() {
        if (uid.isBlank()) return

        HistoricoPalpitesNuvem.atualizarResultados(
            uid,
            jogos.toList()
        ) { atualizados, mensagem ->
            if (atualizados != null) {
                historico.clear()
                historico.addAll(atualizados)
                erroHistorico = ""
            } else {
                erroHistorico = mensagem ?: "Não foi possível atualizar o histórico."
            }
        }
    }

    fun carregarDados() {
        carregando = true
        erro = ""
        jogosProntos = false
        tabelaPronta = false

        DadosBrasileirao.buscarJogos { dados, mensagem ->
            if (dados != null) {
                jogos.clear()
                val lista = dados.optJSONArray("jogos")

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        lista.optJSONObject(i)?.let { jogos.add(it) }
                    }
                }

                jogosProntos = true
            } else {
                erro = mensagem ?: "Erro ao buscar jogos."
                carregando = false
            }
        }

        DadosBrasileirao.buscarClassificacao { dados, mensagem ->
            if (dados != null) {
                clubes.clear()
                val lista = dados.optJSONArray("classificacao")

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        lista.optJSONObject(i)?.let { clubes.add(it) }
                    }
                }

                tabelaPronta = true
            } else {
                erro = mensagem ?: "Erro ao buscar classificação."
                carregando = false
            }
        }

        DadosBrasileirao.buscarHistorico { dados, mensagem ->
            jogosHistoricos.clear()
            val listaHistorica = dados?.optJSONArray("jogos")
            if (listaHistorica != null) {
                for (i in 0 until listaHistorica.length()) {
                    val jogo = listaHistorica.optJSONObject(i) ?: continue
                    val temporada = jogo.optInt(
                        "temporada",
                        jogo.optString("data").take(4).toIntOrNull() ?: 0
                    )
                    // Mantém somente o Brasileirão 2025 como base histórica.
                    if (temporada == 2025) jogosHistoricos.add(jogo)
                }
            }

            baseHistoricaPronta = true
            erroBaseHistorica = when {
                dados == null -> mensagem ?: "Não foi possível carregar a base histórica."
                jogosHistoricos.isEmpty() -> "A API não retornou partidas do Brasileirão 2025."
                else -> "Base histórica 2025: ${jogosHistoricos.size} jogos."
            }
        }
    }

    // O motor consulta apenas 2025 como temporada histórica e acrescenta
    // os resultados já disponíveis do calendário atual de 2026.
    val todosJogosMotor = remember(jogos.size, jogosHistoricos.size) {
        (jogosHistoricos.toList() + jogos.toList())
            .distinctBy { jogo ->
                jogo.optString("id").ifBlank {
                    jogo.optString("data") + "_" +
                        jogo.optJSONObject("mandante")?.optString("nome").orEmpty() + "_" +
                        jogo.optJSONObject("visitante")?.optString("nome").orEmpty()
                }
            }
            .sortedBy { it.optString("data") }
    }

    LaunchedEffect(Unit) {
        carregarDados()
    }

    LaunchedEffect(jogosProntos, tabelaPronta) {
        if (jogosProntos && tabelaPronta) {
            carregando = false

            if (jogos.isEmpty() || clubes.isEmpty()) {
                erro = "Não há dados suficientes para gerar os palpites."
            }
        }
    }

    fun formatarData(data: String): String {
        return try {
            val entrada = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss",
                java.util.Locale.US
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

            val convertida = entrada.parse(data.take(19))
                ?: return "Data a confirmar"

            val saida = java.text.SimpleDateFormat(
                "dd/MM/yyyy • HH:mm",
                java.util.Locale("pt", "BR")
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
            }

            saida.format(convertida)
        } catch (e: Exception) {
            "Data a confirmar"
        }
    }

    fun partidaComecou(data: String): Boolean {
        val inicio = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(data.take(19))?.time
        } catch (_: Exception) {
            null
        }
        return inicio == null || System.currentTimeMillis() >= inicio
    }

    val jogosPendentes = jogos.filter {
        val situacao = it.optString("situacao").uppercase()

        situacao != "FINISHED" &&
        situacao != "FINALIZADO" &&
        situacao != "AWARDED"
    }

    val rodadasDisponiveis = jogos
        .map { it.optInt("rodada") }
        .filter { it > 0 }
        .distinct()
        .sorted()

    val rodadaAtual = jogosPendentes
        .minOfOrNull { it.optInt("rodada") }
        ?: rodadasDisponiveis.firstOrNull()
        ?: 0

    val rodadaExibida = if (rodadaSelecionada in rodadasDisponiveis) {
        rodadaSelecionada
    } else {
        rodadaAtual
    }

    val proximosJogos = jogosPendentes
        .filter { it.optInt("rodada") == rodadaExibida }
        .sortedBy { it.optString("data") }

    val finalizados = historico.filter {
        it.optBoolean("finalizado") &&
            it.optString("origem") != "SIMULACAO_HISTORICA" &&
            it.has("placarCasa") && it.has("placarFora")
    }

    val finalizadosUsuario = historico.filter {
        it.optBoolean("finalizado") && it.has("usuarioPlacarCasa") && it.has("usuarioPlacarFora")
    }
    val acertosUsuario = finalizadosUsuario.count { it.optBoolean("usuarioAcertouResultado") }
    val acertosPlacarUsuario = finalizadosUsuario.count { it.optBoolean("usuarioAcertouPlacar") }
    val taxaUsuario = if (finalizadosUsuario.isNotEmpty()) acertosUsuario * 100 / finalizadosUsuario.size else 0
    val taxaPlacarUsuario = if (finalizadosUsuario.isNotEmpty()) acertosPlacarUsuario * 100 / finalizadosUsuario.size else 0

    val simulados = historico.filter {
        val temporada = it.optInt(
            "temporada",
            it.optString("data").take(4).toIntOrNull() ?: 0
        )
        it.optBoolean("finalizado") &&
            it.optString("origem") == "SIMULACAO_HISTORICA" &&
            temporada == 2025
    }

    val acertosSimulados = simulados.count { it.optBoolean("acertouResultado") }
    val taxaSimulada = if (simulados.isNotEmpty()) {
        (acertosSimulados * 100) / simulados.size
    } else 0

    // Diagnóstico por tipo de resultado previsto e nível de confiança.
    fun probabilidade(palpite: JSONObject, exata: String, inteira: String): Double =
        palpite.optDouble(exata, palpite.optDouble(inteira).toDouble())

    fun resultadoPrevisto(palpite: JSONObject): String {
        val casa = probabilidade(palpite, "probCasaExata", "probCasa")
        val empate = probabilidade(palpite, "probEmpateExata", "probEmpate")
        val fora = probabilidade(palpite, "probForaExata", "probFora")
        return when {
            casa >= empate && casa >= fora -> "CASA"
            empate >= fora -> "EMPATE"
            else -> "FORA"
        }
    }

    fun taxaDoGrupo(grupo: List<JSONObject>): String {
        if (grupo.isEmpty()) return "Sem amostra"
        val acertosGrupo = grupo.count { it.optBoolean("acertouResultado") }
        return "${(acertosGrupo * 100) / grupo.size}% (${acertosGrupo}/${grupo.size})"
    }

    val simuladosCasa = simulados.filter { resultadoPrevisto(it) == "CASA" }
    val simuladosEmpate = simulados.filter { resultadoPrevisto(it) == "EMPATE" }
    val simuladosFora = simulados.filter { resultadoPrevisto(it) == "FORA" }

    // Experimento paralelo: Dixon-Coles em cima dos gols esperados já salvos.
    // Não altera os palpites oficiais nem grava resultados no histórico.
    val candidatosDixonColes = listOf(-0.05, -0.10, -0.15, -0.20, -0.25).map { rho ->
        val avaliaveis = simulados.filter {
            it.has("golsEsperadosCasa") && it.has("golsEsperadosFora") &&
                it.has("resultadoReal")
        }
        val avaliados = avaliaveis.map { partida ->
            val p = MotorPalpites.probabilidadesDixonColes(
                partida.optDouble("golsEsperadosCasa"),
                partida.optDouble("golsEsperadosFora"),
                rho
            )
            val previsto = when {
                p.casa >= p.empate && p.casa >= p.fora -> "CASA"
                p.empate >= p.fora -> "EMPATE"
                else -> "FORA"
            }
            Triple(previsto, partida.optString("resultadoReal"), p.empate)
        }
        val acertos = avaliados.count { it.first == it.second }
        val empatesEscolhidos = avaliados.count { it.first == "EMPATE" }
        val acertosEmpate = avaliados.count { it.first == "EMPATE" && it.second == "EMPATE" }
        Triple(rho, avaliados.size, Triple(acertos, empatesEscolhidos, acertosEmpate))
    }

    // Validação temporal: escolhe rho somente no bloco mais antigo
    // e mede o desempenho no bloco posterior, que fica fora do ajuste.
    val jogosDixonOrdenados = simulados.filter {
        it.has("golsEsperadosCasa") && it.has("golsEsperadosFora") &&
            it.has("resultadoReal") && it.optString("data").isNotBlank()
    }.sortedBy { it.optString("data") }

    fun acertosDixonNoGrupo(grupo: List<JSONObject>, rho: Double): Pair<Int, Int> {
        var acertos = 0
        grupo.forEach { partida ->
            val p = MotorPalpites.probabilidadesDixonColes(
                partida.optDouble("golsEsperadosCasa"),
                partida.optDouble("golsEsperadosFora"),
                rho
            )
            val previsto = when {
                p.casa >= p.empate && p.casa >= p.fora -> "CASA"
                p.empate >= p.fora -> "EMPATE"
                else -> "FORA"
            }
            if (previsto == partida.optString("resultadoReal")) acertos++
        }
        return acertos to grupo.size
    }

    val indiceSeparacao = (jogosDixonOrdenados.size * 0.70).toInt()
    val validacaoTemporalDisponivel = jogosDixonOrdenados.size >= 20 &&
        indiceSeparacao in 1 until jogosDixonOrdenados.size
    val treinoDixon = if (validacaoTemporalDisponivel)
        jogosDixonOrdenados.take(indiceSeparacao) else emptyList()
    val testeDixon = if (validacaoTemporalDisponivel)
        jogosDixonOrdenados.drop(indiceSeparacao) else emptyList()

    // Experimento de empate contextual: combina equilíbrio dos gols esperados,
    // baixo volume de gols e frequência de empates observada somente no treino.
    val taxaEmpateHistoricaTreino = if (treinoDixon.isNotEmpty()) {
        (treinoDixon.count { it.optString("resultadoReal") == "EMPATE" } + 5.4) /
            (treinoDixon.size + 20.0) * 100.0
    } else 27.0

    fun probabilidadesEmpateContextual(
        partida: JSONObject,
        intensidade: Double
    ): Triple<Double, Double, Double> {
        val casaBase = probabilidade(partida, "probCasaExata", "probCasa")
        val empateBase = probabilidade(partida, "probEmpateExata", "probEmpate")
        val foraBase = probabilidade(partida, "probForaExata", "probFora")
        val golsCasa = partida.optDouble("golsEsperadosCasa")
        val golsFora = partida.optDouble("golsEsperadosFora")
        val diferenca = kotlin.math.abs(golsCasa - golsFora)
        val totalGols = golsCasa + golsFora
        val equilibrio = kotlin.math.exp(-1.8 * diferenca)
        val baixoVolume = 1.0 / (1.0 + kotlin.math.exp((totalGols - 2.4) * 1.5))
        val alvoContextual = taxaEmpateHistoricaTreino * 0.40 +
            (18.0 + 18.0 * equilibrio + 10.0 * baixoVolume) * 0.60
        val empateNovo = (empateBase + intensidade * (alvoContextual - empateBase))
            .coerceIn(5.0, 60.0)
        val somaNaoEmpate = (casaBase + foraBase).coerceAtLeast(0.001)
        val restante = 100.0 - empateNovo
        return Triple(
            restante * casaBase / somaNaoEmpate,
            empateNovo,
            restante * foraBase / somaNaoEmpate
        )
    }

    fun avaliarEmpateContextual(grupo: List<JSONObject>, intensidade: Double): Triple<Int, Int, Int> {
        var acertos = 0
        var empatesPrevistos = 0
        var empatesCertos = 0
        grupo.forEach { partida ->
            val p = probabilidadesEmpateContextual(partida, intensidade)
            val previsto = when {
                p.first >= p.second && p.first >= p.third -> "CASA"
                p.second >= p.third -> "EMPATE"
                else -> "FORA"
            }
            val real = partida.optString("resultadoReal")
            if (previsto == real) acertos++
            if (previsto == "EMPATE") {
                empatesPrevistos++
                if (real == "EMPATE") empatesCertos++
            }
        }
        return Triple(acertos, empatesPrevistos, empatesCertos)
    }

    fun macroF1EmpateContextual(grupo: List<JSONObject>, intensidade: Double): Double {
        if (grupo.isEmpty()) return 0.0
        val classes = listOf("CASA", "EMPATE", "FORA")
        return classes.map { classe ->
            var tp = 0
            var fp = 0
            var fn = 0
            grupo.forEach { partida ->
                val p = probabilidadesEmpateContextual(partida, intensidade)
                val previsto = when {
                    p.first >= p.second && p.first >= p.third -> "CASA"
                    p.second >= p.third -> "EMPATE"
                    else -> "FORA"
                }
                val real = partida.optString("resultadoReal")
                if (previsto == classe && real == classe) tp++
                else if (previsto == classe) fp++
                else if (real == classe) fn++
            }
            val precisao = tp.toDouble() / (tp + fp).coerceAtLeast(1)
            val recall = tp.toDouble() / (tp + fn).coerceAtLeast(1)
            if (precisao + recall == 0.0) 0.0 else 2.0 * precisao * recall / (precisao + recall)
        }.average()
    }

    fun brierEmpateContextual(grupo: List<JSONObject>, intensidade: Double): Double {
        if (grupo.isEmpty()) return 0.0
        var soma = 0.0
        grupo.forEach { partida ->
            val p = probabilidadesEmpateContextual(partida, intensidade)
            val real = partida.optString("resultadoReal")
            val alvos = listOf(real == "CASA", real == "EMPATE", real == "FORA")
            val valores = listOf(p.first, p.second, p.third)
            soma += valores.indices.sumOf { i ->
                val erro = valores[i] / 100.0 - if (alvos[i]) 1.0 else 0.0
                erro * erro
            }
        }
        return soma / grupo.size
    }

    val intensidadesEmpate = listOf(0.25, 0.50, 0.75, 1.0)
    val intensidadeEmpateEscolhida = if (validacaoTemporalDisponivel) {
        intensidadesEmpate.maxByOrNull { macroF1EmpateContextual(treinoDixon, it) } ?: 0.50
    } else 0.50
    val resultadoEmpateContextualTeste = if (validacaoTemporalDisponivel)
        avaliarEmpateContextual(testeDixon, intensidadeEmpateEscolhida) else Triple(0, 0, 0)
    val brierEmpateContextualTeste = if (validacaoTemporalDisponivel)
        brierEmpateContextual(testeDixon, intensidadeEmpateEscolhida) else 0.0
    val macroF1EmpateContextualTeste = if (validacaoTemporalDisponivel)
        macroF1EmpateContextual(testeDixon, intensidadeEmpateEscolhida) else 0.0

    val rhoEscolhido = if (validacaoTemporalDisponivel) {
        listOf(0.0, -0.05, -0.10, -0.15, -0.20, -0.25)
            .map { rho -> rho to acertosDixonNoGrupo(treinoDixon, rho) }
            .maxWithOrNull(
                compareBy<Pair<Double, Pair<Int, Int>>> { (_, resultado) ->
                    if (resultado.second > 0) resultado.first.toDouble() / resultado.second else 0.0
                }.thenBy { (rho, _) -> -kotlin.math.abs(rho) }
            )?.first ?: 0.0
    } else 0.0
    val resultadoBaseTeste = if (validacaoTemporalDisponivel)
        acertosDixonNoGrupo(testeDixon, 0.0) else 0 to 0
    val resultadoAjustadoTeste = if (validacaoTemporalDisponivel)
        acertosDixonNoGrupo(testeDixon, rhoEscolhido) else 0 to 0

    // Laboratorio de mando de campo: testa reducoes graduais na media de gols
    // esperada do mandante. Ajuste escolhido apenas no treino e avaliado no futuro.
    fun avaliarFatorMandante(grupo: List<JSONObject>, fator: Double): Triple<Int, Int, Int> {
        var acertos = 0
        var empatesPrevistos = 0
        var visitantesPrevistos = 0
        grupo.forEach { partida ->
            val p = MotorPalpites.probabilidadesComFatorMandante(
                partida.optDouble("golsEsperadosCasa"),
                partida.optDouble("golsEsperadosFora"),
                fator
            )
            val previsto = when {
                p.casa >= p.empate && p.casa >= p.fora -> "CASA"
                p.empate >= p.fora -> "EMPATE"
                else -> "FORA"
            }
            if (previsto == partida.optString("resultadoReal")) acertos++
            if (previsto == "EMPATE") empatesPrevistos++
            if (previsto == "FORA") visitantesPrevistos++
        }
        return Triple(acertos, empatesPrevistos, visitantesPrevistos)
    }

    val fatoresMandante = listOf(0.80, 0.85, 0.90, 0.95, 1.00)
    val fatorMandanteEscolhido = if (validacaoTemporalDisponivel) {
        fatoresMandante.map { fator ->
            fator to avaliarFatorMandante(treinoDixon, fator)
        }.maxWithOrNull(
            compareBy<Pair<Double, Triple<Int, Int, Int>>> { (_, resultado) ->
                if (treinoDixon.isNotEmpty()) resultado.first.toDouble() / treinoDixon.size else 0.0
            }.thenBy { (fator, _) -> fator }
        )?.first ?: 1.0
    } else 1.0
    val resultadoMandoBaseTeste = if (validacaoTemporalDisponivel)
        avaliarFatorMandante(testeDixon, 1.0) else Triple(0, 0, 0)
    val resultadoMandoAjustadoTeste = if (validacaoTemporalDisponivel)
        avaliarFatorMandante(testeDixon, fatorMandanteEscolhido) else Triple(0, 0, 0)

    // Laboratorio de decisao do empate: permite selecionar empate quando sua
    // probabilidade fica suficientemente proxima da maior probabilidade rival.
    // O limiar e escolhido no treino temporal e medido no bloco futuro.
    fun avaliarLimiarEmpate(grupo: List<JSONObject>, limiar: Double): Triple<Int, Int, Int> {
        var acertos = 0
        var empatesPrevistos = 0
        var empatesCertos = 0
        grupo.forEach { partida ->
            val pCasa = probabilidade(partida, "probCasaExata", "probCasa")
            val pEmpate = probabilidade(partida, "probEmpateExata", "probEmpate")
            val pFora = probabilidade(partida, "probForaExata", "probFora")
            val maiorRival = maxOf(pCasa, pFora)
            val previsto = if (pEmpate >= maiorRival * limiar) "EMPATE" else if (pCasa >= pFora) "CASA" else "FORA"
            val real = partida.optString("resultadoReal")
            if (previsto == real) acertos++
            if (previsto == "EMPATE") {
                empatesPrevistos++
                if (real == "EMPATE") empatesCertos++
            }
        }
        return Triple(acertos, empatesPrevistos, empatesCertos)
    }

    val limiaresEmpate = listOf(0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.00)

    fun macroF1(grupo: List<JSONObject>, limiar: Double): Double {
        if (grupo.isEmpty()) return 0.0
        val classes = listOf("CASA", "EMPATE", "FORA")
        val f1s = classes.map { classe ->
            var verdadeirosPositivos = 0
            var falsosPositivos = 0
            var falsosNegativos = 0
            grupo.forEach { partida ->
                val pCasa = probabilidade(partida, "probCasaExata", "probCasa")
                val pEmpate = probabilidade(partida, "probEmpateExata", "probEmpate")
                val pFora = probabilidade(partida, "probForaExata", "probFora")
                val maiorRival = maxOf(pCasa, pFora)
                val previsto = if (pEmpate >= maiorRival * limiar) "EMPATE"
                    else if (pCasa >= pFora) "CASA" else "FORA"
                val real = partida.optString("resultadoReal")
                if (previsto == classe && real == classe) verdadeirosPositivos++
                else if (previsto == classe) falsosPositivos++
                else if (real == classe) falsosNegativos++
            }
            val precisao = verdadeirosPositivos.toDouble() /
                (verdadeirosPositivos + falsosPositivos).coerceAtLeast(1)
            val recall = verdadeirosPositivos.toDouble() /
                (verdadeirosPositivos + falsosNegativos).coerceAtLeast(1)
            if (precisao + recall == 0.0) 0.0 else 2.0 * precisao * recall / (precisao + recall)
        }
        return f1s.average()
    }

    fun relatorioMetricas(grupo: List<JSONObject>, limiar: Double): String {
        if (grupo.isEmpty()) return "Sem amostra para métricas avançadas."
        val classes = listOf("CASA", "EMPATE", "FORA")
        val nomes = listOf("Casa", "Empate", "Fora")
        val linhas = classes.indices.map { indice ->
            val classe = classes[indice]
            var tp = 0
            var fp = 0
            var fn = 0
            grupo.forEach { partida ->
                val pCasa = probabilidade(partida, "probCasaExata", "probCasa")
                val pEmpate = probabilidade(partida, "probEmpateExata", "probEmpate")
                val pFora = probabilidade(partida, "probForaExata", "probFora")
                val previsto = if (pEmpate >= maxOf(pCasa, pFora) * limiar) "EMPATE"
                    else if (pCasa >= pFora) "CASA" else "FORA"
                val real = partida.optString("resultadoReal")
                if (previsto == classe && real == classe) tp++
                else if (previsto == classe) fp++
                else if (real == classe) fn++
            }
            val precisao = tp.toDouble() / (tp + fp).coerceAtLeast(1)
            val recall = tp.toDouble() / (tp + fn).coerceAtLeast(1)
            val f1 = if (precisao + recall > 0) 2 * precisao * recall / (precisao + recall) else 0.0
            "${nomes[indice]}: precisão ${"%.1f".format(java.util.Locale.US, precisao * 100)}% • recall ${"%.1f".format(java.util.Locale.US, recall * 100)}% • F1 ${"%.1f".format(java.util.Locale.US, f1 * 100)}%"
        }
        var brier = 0.0
        grupo.forEach { partida ->
            val probabilidades = listOf(
                probabilidade(partida, "probCasaExata", "probCasa") / 100.0,
                probabilidade(partida, "probEmpateExata", "probEmpate") / 100.0,
                probabilidade(partida, "probForaExata", "probFora") / 100.0
            )
            val real = partida.optString("resultadoReal")
            val alvos = listOf(real == "CASA", real == "EMPATE", real == "FORA")
            brier += probabilidades.indices.sumOf { i ->
                val erro = probabilidades[i] - if (alvos[i]) 1.0 else 0.0
                erro * erro
            }
        }
        brier /= grupo.size
        return linhas.joinToString("\n") +
            "\\nMacro-F1: ${"%.1f".format(java.util.Locale.US, macroF1(grupo, limiar) * 100)}%" +
            "\\nBrier multiclasses (menor é melhor): ${"%.4f".format(java.util.Locale.US, brier)}"
    }

    // O limiar agora é escolhido pelo Macro-F1 no treino, equilibrando casa,
    // empate e visitante. A acurácia continua sendo reportada, mas não decide sozinha.
    val limiarEmpateEscolhido = if (validacaoTemporalDisponivel) {
        limiaresEmpate.map { limiar -> limiar to macroF1(treinoDixon, limiar) }
            .maxWithOrNull(
                compareBy<Pair<Double, Double>> { it.second }
                    .thenBy { limiar -> avaliarLimiarEmpate(treinoDixon, limiar.first).first }
                    .thenBy { it.first }
            )?.first ?: 1.0
    } else 1.0
    val resultadoEmpateBaseTreino = if (validacaoTemporalDisponivel) avaliarLimiarEmpate(treinoDixon, 1.0) else Triple(0, 0, 0)
    val resultadoEmpateAjustadoTreino = if (validacaoTemporalDisponivel) avaliarLimiarEmpate(treinoDixon, limiarEmpateEscolhido) else Triple(0, 0, 0)
    val resultadoEmpateBaseTeste = if (validacaoTemporalDisponivel) avaliarLimiarEmpate(testeDixon, 1.0) else Triple(0, 0, 0)
    val resultadoEmpateAjustadoTeste = if (validacaoTemporalDisponivel) avaliarLimiarEmpate(testeDixon, limiarEmpateEscolhido) else Triple(0, 0, 0)
    val metricasBaseTreino = relatorioMetricas(treinoDixon, 1.0)
    val metricasAjustadasTreino = relatorioMetricas(treinoDixon, limiarEmpateEscolhido)
    val metricasBaseTeste = relatorioMetricas(testeDixon, 1.0)
    val metricasAjustadasTeste = relatorioMetricas(testeDixon, limiarEmpateEscolhido)

    // Compara modelos usando exatamente o mesmo bloco futuro (30% mais recente).
    fun avaliarCamposModelo(
        grupo: List<JSONObject>,
        chaves: List<String>
    ): Triple<Int, Int, Double> {
        if (grupo.isEmpty()) return Triple(0, 0, 0.0)
        var acertosModelo = 0
        var jogosValidos = 0
        var brierModelo = 0.0
        grupo.forEach { partida ->
            val probs = chaves.map { partida.optDouble(it, Double.NaN) }
            if (probs.any { !it.isFinite() }) return@forEach
            val real = partida.optString("resultadoReal")
            if (real !in listOf("CASA", "EMPATE", "FORA")) return@forEach
            jogosValidos++
            val previsto = when (probs.indices.maxByOrNull { probs[it] } ?: 0) {
                0 -> "CASA"
                1 -> "EMPATE"
                else -> "FORA"
            }
            if (previsto == real) acertosModelo++
            val alvos = listOf(real == "CASA", real == "EMPATE", real == "FORA")
            brierModelo += probs.indices.sumOf { i ->
                val erro = probs[i] / 100.0 - if (alvos[i]) 1.0 else 0.0
                erro * erro
            }
        }
        return Triple(acertosModelo, jogosValidos, if (jogosValidos > 0) brierModelo / jogosValidos else 0.0)
    }

    val avaliacaoAtualTeste = avaliarCamposModelo(
        testeDixon, listOf("probCasaExata", "probEmpateExata", "probForaExata")
    )
    val avaliacaoEloTeste = avaliarCamposModelo(
        testeDixon, listOf("eloProbCasa", "eloProbEmpate", "eloProbFora")
    )
    val avaliacaoCombinadoTeste = avaliarCamposModelo(
        testeDixon, listOf("combinadoProbCasa", "combinadoProbEmpate", "combinadoProbFora")
    )

    // Raio-X específico para entender por que o empate não é escolhido.
    val probabilidadesEmpate = simulados.map {
        probabilidade(it, "probEmpateExata", "probEmpate")
    }.filter { it.isFinite() }
    val mediaProbEmpate = probabilidadesEmpate.average().takeIf { it.isFinite() } ?: 0.0
    val maiorProbEmpate = probabilidadesEmpate.maxOrNull() ?: 0.0
    val partidasComGolsEsperados = simulados.filter {
        it.has("golsEsperadosCasa") && it.has("golsEsperadosFora")
    }
    val mediaGolsEsperadosCasa = partidasComGolsEsperados.map {
        it.optDouble("golsEsperadosCasa")
    }.average().takeIf { it.isFinite() } ?: 0.0
    val mediaGolsEsperadosFora = partidasComGolsEsperados.map {
        it.optDouble("golsEsperadosFora")
    }.average().takeIf { it.isFinite() } ?: 0.0
    val diferencaMediaGolsEsperados = partidasComGolsEsperados.map {
        kotlin.math.abs(it.optDouble("golsEsperadosCasa") - it.optDouble("golsEsperadosFora"))
    }.average().takeIf { it.isFinite() } ?: 0.0
    val jogosComGolsProximos = partidasComGolsEsperados.count {
        kotlin.math.abs(it.optDouble("golsEsperadosCasa") - it.optDouble("golsEsperadosFora")) <= 0.25
    }
    val empatesAteCincoPontos = simulados.count { palpite ->
        val empate = probabilidade(palpite, "probEmpateExata", "probEmpate")
        val maiorAdversaria = maxOf(
            probabilidade(palpite, "probCasaExata", "probCasa"),
            probabilidade(palpite, "probForaExata", "probFora")
        )
        empate.isFinite() && maiorAdversaria.isFinite() &&
            maiorAdversaria >= empate && maiorAdversaria - empate <= 5.0
    }
    val simuladosAltaConf = simulados.filter {
        maxOf(
            probabilidade(it, "probCasaExata", "probCasa"),
            probabilidade(it, "probEmpateExata", "probEmpate"),
            probabilidade(it, "probForaExata", "probFora")
        ) >= 50.0
    }
    val simuladosMediaConf = simulados.filter {
        val maior = maxOf(
            probabilidade(it, "probCasaExata", "probCasa"),
            probabilidade(it, "probEmpateExata", "probEmpate"),
            probabilidade(it, "probForaExata", "probFora")
        )
        maior >= 40.0 && maior < 50.0
    }
    val simuladosBaixaConf = simulados.filter {
        maxOf(
            probabilidade(it, "probCasaExata", "probCasa"),
            probabilidade(it, "probEmpateExata", "probEmpate"),
            probabilidade(it, "probForaExata", "probFora")
        ) < 40.0
    }

    // Compara a distribuição real dos resultados com as probabilidades
    // que o motor atribuiu a cada classe. Isso revela viés sem alterar previsões.
    val simuladosCasaReal = simulados.filter { it.optString("resultadoReal") == "CASA" }
    val simuladosEmpateReal = simulados.filter { it.optString("resultadoReal") == "EMPATE" }
    val simuladosForaReal = simulados.filter { it.optString("resultadoReal") == "FORA" }

    fun mediaProbabilidade(grupo: List<JSONObject>, exata: String, inteira: String): Double {
        val valores = grupo.map { probabilidade(it, exata, inteira) }.filter { it.isFinite() }
        return valores.average().takeIf { valores.isNotEmpty() && it.isFinite() } ?: 0.0
    }

    val mediaProbCasaEmVitoriasCasa = mediaProbabilidade(simuladosCasaReal, "probCasaExata", "probCasa")
    val mediaProbEmpateEmEmpates = mediaProbabilidade(simuladosEmpateReal, "probEmpateExata", "probEmpate")
    val mediaProbForaEmVitoriasFora = mediaProbabilidade(simuladosForaReal, "probForaExata", "probFora")

    // Acerto de resultado significa acertar Casa/Empate/Fora (1X2).
    val acertos = finalizados.count {
        it.optBoolean("acertouResultado")
    }

    val taxaAcerto = if (finalizados.isNotEmpty()) {
        (acertos * 100) / finalizados.size
    } else {
        0
    }

    // Placar exato é uma métrica independente e mais rigorosa.
    val acertosPlacar = finalizados.count {
        it.optBoolean("acertouPlacar")
    }

    val taxaPlacar = if (finalizados.isNotEmpty()) {
        (acertosPlacar * 100) / finalizados.size
    } else {
        0
    }

    val relatorioDiagnostico = buildString {
        appendLine("DIAGNÓSTICO DOS ERROS")
        appendLine("Acerto por resultado previsto")
        appendLine("Vitória da casa: ${taxaDoGrupo(simuladosCasa)}")
        appendLine("Empate: ${taxaDoGrupo(simuladosEmpate)}")
        appendLine("Vitória de fora: ${taxaDoGrupo(simuladosFora)}")
        appendLine("DISTRIBUIÇÃO DOS RESULTADOS REAIS")
        appendLine("Vitórias da casa: ${simuladosCasaReal.size} • Empates: ${simuladosEmpateReal.size} • Vitórias de fora: ${simuladosForaReal.size}")
        appendLine("Probabilidade média dada à casa quando ela venceu: ${"%.1f".format(java.util.Locale.US, mediaProbCasaEmVitoriasCasa)}%")
        appendLine("Probabilidade média de empate nos empates reais: ${"%.1f".format(java.util.Locale.US, mediaProbEmpateEmEmpates)}%")
        appendLine("Probabilidade média dada ao visitante quando ele venceu: ${"%.1f".format(java.util.Locale.US, mediaProbForaEmVitoriasFora)}%")
        appendLine("Acerto por confiança máxima")
        appendLine("50% ou mais: ${taxaDoGrupo(simuladosAltaConf)}")
        appendLine("40% a 49%: ${taxaDoGrupo(simuladosMediaConf)}")
        appendLine("Abaixo de 40%: ${taxaDoGrupo(simuladosBaixaConf)}")
        appendLine("TESTE PARALELO: DIXON-COLES")
        candidatosDixonColes.forEach { teste ->
            appendLine("ρ ${"%.2f".format(java.util.Locale.US, teste.first)}: ${if (teste.second > 0) teste.third.first * 100 / teste.second else 0}% de acerto (${teste.third.first}/${teste.second}) • empates previstos: ${teste.third.second} • empates certos: ${teste.third.third}")
        }
        appendLine("VALIDAÇÃO TEMPORAL (70% TREINO / 30% TESTE)")
        appendLine("Ajuste escolhido: ρ ${"%.2f".format(java.util.Locale.US, rhoEscolhido)}")
        appendLine("Treino: ${treinoDixon.size} jogos • Teste futuro: ${testeDixon.size} jogos")
        appendLine("Modelo original no teste: ${resultadoBaseTeste.first}/${resultadoBaseTeste.second}")
        appendLine("Modelo experimental no teste: ${resultadoAjustadoTeste.first}/${resultadoAjustadoTeste.second}")
        appendLine("EXPERIMENTO: EMPATE CONTEXTUAL (TREINO SOMENTE)")
        appendLine("Sinais: equilíbrio dos gols esperados + baixo total de gols + frequência histórica de empates.")
        appendLine("Frequência histórica suavizada no treino: ${"%.1f".format(java.util.Locale.US, taxaEmpateHistoricaTreino)}%")
        appendLine("Intensidade escolhida por Macro-F1 no treino: ${"%.2f".format(java.util.Locale.US, intensidadeEmpateEscolhida)}")
        appendLine("Teste futuro: ${resultadoEmpateContextualTeste.first}/${testeDixon.size} • empates previstos: ${resultadoEmpateContextualTeste.second} • empates certos: ${resultadoEmpateContextualTeste.third}")
        appendLine("Macro-F1 contextual: ${"%.1f".format(java.util.Locale.US, macroF1EmpateContextualTeste * 100)}% • Brier: ${"%.4f".format(java.util.Locale.US, brierEmpateContextualTeste)}")
        appendLine("COMPARAÇÃO DE MODELOS NO MESMO TESTE FUTURO")
        appendLine("Motor atual: ${avaliacaoAtualTeste.first}/${avaliacaoAtualTeste.second} • Brier ${"%.4f".format(java.util.Locale.US, avaliacaoAtualTeste.third)}")
        appendLine("Elo: ${avaliacaoEloTeste.first}/${avaliacaoEloTeste.second} • Brier ${"%.4f".format(java.util.Locale.US, avaliacaoEloTeste.third)}")
        appendLine("Combinação 50/50: ${avaliacaoCombinadoTeste.first}/${avaliacaoCombinadoTeste.second} • Brier ${"%.4f".format(java.util.Locale.US, avaliacaoCombinadoTeste.third)}")
        if (listOf(avaliacaoAtualTeste, avaliacaoEloTeste, avaliacaoCombinadoTeste).any { it.second < testeDixon.size }) {
            appendLine("Atenção: alguns jogos não têm probabilidades salvas para todos os modelos. Gere novamente a simulação histórica.")
        }
        appendLine("LABORATÓRIO: VANTAGEM DO MANDANTE")
        fatoresMandante.forEach { fator ->
            val resultado = avaliarFatorMandante(treinoDixon, fator)
            appendLine("Fator ${"%.2f".format(java.util.Locale.US, fator)}: ${resultado.first}/${treinoDixon.size} acertos • empates: ${resultado.second} • vitórias fora: ${resultado.third}")
        }
        appendLine("Melhor fator no treino: ${"%.2f".format(java.util.Locale.US, fatorMandanteEscolhido)}")
        appendLine("Teste futuro, modelo atual: ${resultadoMandoBaseTeste.first}/${testeDixon.size} • empates: ${resultadoMandoBaseTeste.second} • fora: ${resultadoMandoBaseTeste.third}")
        appendLine("Teste futuro, fator ajustado: ${resultadoMandoAjustadoTeste.first}/${testeDixon.size} • empates: ${resultadoMandoAjustadoTeste.second} • fora: ${resultadoMandoAjustadoTeste.third}")
        appendLine("LABORATÓRIO: DECISÃO DE EMPATE")
        limiaresEmpate.forEach { limiar ->
            val resultado = avaliarLimiarEmpate(treinoDixon, limiar)
            appendLine("Limiar ${"%.2f".format(java.util.Locale.US, limiar)}: ${resultado.first}/${treinoDixon.size} acertos • empates: ${resultado.second} • certos: ${resultado.third}")
        }
        appendLine("Melhor limiar no treino: ${"%.2f".format(java.util.Locale.US, limiarEmpateEscolhido)}")
        appendLine("Treino, modelo atual: ${resultadoEmpateBaseTreino.first}/${treinoDixon.size} • ajustado: ${resultadoEmpateAjustadoTreino.first}/${treinoDixon.size}")
        appendLine("Teste futuro, modelo atual: ${resultadoEmpateBaseTeste.first}/${testeDixon.size} • empates: ${resultadoEmpateBaseTeste.second} • certos: ${resultadoEmpateBaseTeste.third}")
        appendLine("Teste futuro, limiar ajustado: ${resultadoEmpateAjustadoTeste.first}/${testeDixon.size} • empates: ${resultadoEmpateAjustadoTeste.second} • certos: ${resultadoEmpateAjustadoTeste.third}")
        appendLine("CALIBRAÇÃO AVANÇADA: PRECISÃO, RECALL, F1 E BRIER")
        appendLine("Limiar escolhido por Macro-F1 no treino: ${"%.2f".format(java.util.Locale.US, limiarEmpateEscolhido)}")
        appendLine("MÉTRICAS NO TESTE FUTURO - MODELO ORIGINAL")
        appendLine(metricasBaseTeste)
        appendLine("MÉTRICAS NO TESTE FUTURO - LIMIAR CALIBRADO")
        appendLine(metricasAjustadasTeste)
        appendLine("MÉTRICAS DE TREINO - MODELO ORIGINAL")
        appendLine(metricasBaseTreino)
        appendLine("MÉTRICAS DE TREINO - LIMIAR CALIBRADO")
        appendLine(metricasAjustadasTreino)
        appendLine("RAIO-X DOS EMPATES")
        appendLine("Partidas analisadas: ${probabilidadesEmpate.size} de ${simulados.size}")
        appendLine("Empates escolhidos pelo motor: ${simuladosEmpate.size}")
        appendLine("Probabilidade média de empate: ${"%.1f".format(java.util.Locale.US, mediaProbEmpate)}%")
        appendLine("Maior probabilidade de empate: ${"%.1f".format(java.util.Locale.US, maiorProbEmpate)}%")
        appendLine("Partidas com dados de gols esperados: ${partidasComGolsEsperados.size} de ${simulados.size}")
        appendLine("Média de gols esperados: ${"%.2f".format(java.util.Locale.US, mediaGolsEsperadosCasa)} casa x ${"%.2f".format(java.util.Locale.US, mediaGolsEsperadosFora)} fora")
        appendLine("Diferença média entre os times: ${"%.2f".format(java.util.Locale.US, diferencaMediaGolsEsperados)} gol")
        appendLine("Jogos com gols esperados separados por até 0,25: $jogosComGolsProximos")
        appendLine("Empate ficou até 5 pontos do favorito: $empatesAteCincoPontos")
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        Text(
            "🎯 PALPITES INTELIGENTES",
            color = corDestaque,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Previsões estatísticas da rodada selecionada",
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(15.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "COMO FUNCIONA",
                    color = corDestaque,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "O motor analisa os últimos cinco jogos, o desempenho em casa e fora, gols marcados e sofridos e a força dos adversários. Um modelo estatístico de Poisson calcula as probabilidades.",
                    color = Color.White,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "As porcentagens são estimativas estatísticas, não garantias de resultado.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        if (carregando) {
            CircularProgressIndicator(color = corDestaque)

            Text(
                "Analisando os clubes e partidas...",
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (erroBaseHistorica.isNotEmpty()) {
            Text(
                erroBaseHistorica,
                color = Color.LightGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (erro.isNotEmpty()) {
            Text(erro, color = Color.White)

            Spacer(modifier = Modifier.height(10.dp))

            Button(onClick = { carregarDados() }) {
                Text("ATUALIZAR DADOS")
            }
        }

        if (!carregando && erro.isEmpty() && jogosProntos && tabelaPronta) {

            Text(
                "ESCOLHA A RODADA",
                color = corDestaque,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box {
                OutlinedButton(
                    onClick = { rodadaMenuAberto = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("RODADA $rodadaExibida  ▼")
                }

                DropdownMenu(
                    expanded = rodadaMenuAberto,
                    onDismissRequest = { rodadaMenuAberto = false }
                ) {
                    rodadasDisponiveis.forEach { numeroRodada ->
                        DropdownMenuItem(
                            text = { Text("Rodada $numeroRodada") },
                            onClick = {
                                rodadaSelecionada = numeroRodada
                                rodadaMenuAberto = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (proximosJogos.isEmpty()) {
                Text(
                    "Não há partidas pendentes nesta rodada.",
                    color = Color.White
                )
            }

            proximosJogos.forEach { jogo ->

                val mandante = jogo.optJSONObject("mandante")
                    ?.optString("nome", "") ?: ""

                val visitante = jogo.optJSONObject("visitante")
                    ?.optString("nome", "") ?: ""

                val idJogo = jogo.optString("id").ifBlank {
                    "${jogo.optString("rodada")}_${jogo.optString("data")}_${mandante}_${visitante}"
                }

                val analise = MotorPalpites.analisar(
                    jogo = jogo,
                    todosJogos = todosJogosMotor,
                    clubes = clubes.toList()
                )

                if (analise != null) {

                    val registroDoJogo = historico.firstOrNull {
                        it.optString("idJogo") == idJogo
                    }
                    val jaSalvo = registroDoJogo?.has("probCasaExata") == true
                    val usuarioJaSalvou = registroDoJogo?.has("usuarioPlacarCasa") == true
                    val partidaBloqueada = partidaComecou(jogo.optString("data"))
                    var golsUsuarioCasa by remember(idJogo, registroDoJogo?.optInt("usuarioPlacarCasa", -1)) {
                        mutableStateOf(registroDoJogo?.optInt("usuarioPlacarCasa", -1)?.takeIf { it >= 0 }?.toString().orEmpty())
                    }
                    var golsUsuarioFora by remember(idJogo, registroDoJogo?.optInt("usuarioPlacarFora", -1)) {
                        mutableStateOf(registroDoJogo?.optInt("usuarioPlacarFora", -1)?.takeIf { it >= 0 }?.toString().orEmpty())
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = corCartao
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                "RODADA ${jogo.optString("rodada")}",
                                color = corDestaque,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )

                            Text(
                                formatarData(jogo.optString("data")),
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EscudoClube(
            url = jogo.optJSONObject("mandante")?.optString("escudo"),
            nome = mandante,
            tamanho = 34.dp
        )

        Spacer(modifier = Modifier.width(6.dp))

        Column {
            Text(
                mandante,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )

            Text(
                "Forma: ${analise.formaCasa}",
                color = corDestaque,
                fontSize = 10.sp
            )
        }
    }

    Text(
        "X",
        color = corDestaque,
        fontWeight = FontWeight.Black,
        fontSize = 18.sp
    )

    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                visitante,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )

            Text(
                "Forma: ${analise.formaFora}",
                color = corDestaque,
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        EscudoClube(
            url = jogo.optJSONObject("visitante")?.optString("escudo"),
            nome = visitante,
            tamanho = 34.dp
        )
    }
}


                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "PROBABILIDADES ESTIMADAS",
                                color = corDestaque,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Casa", color = Color.White, fontSize = 12.sp)
                                Text("Empate", color = Color.White, fontSize = 12.sp)
                                Text("Fora", color = Color.White, fontSize = 12.sp)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${analise.probCasa}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "${analise.probEmpate}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "${analise.probFora}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "🎯 PLACAR ESTIMADO: ${analise.placarCasa} x ${analise.placarFora}",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Divider(color = corDestaque.copy(alpha = 0.45f))

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                "🙋 SEU PALPITE",
                                color = corDestaque,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                "Faça seu próprio placar. Ele fica salvo na sua conta e será avaliado após o jogo.",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = golsUsuarioCasa,
                                    onValueChange = { valor ->
                                        if (valor.length <= 2 && valor.all { it.isDigit() }) golsUsuarioCasa = valor
                                    },
                                    label = { Text("Gols casa") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    enabled = !partidaBloqueada,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = golsUsuarioFora,
                                    onValueChange = { valor ->
                                        if (valor.length <= 2 && valor.all { it.isDigit() }) golsUsuarioFora = valor
                                    },
                                    label = { Text("Gols fora") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    enabled = !partidaBloqueada,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            if (usuarioJaSalvou) {
                                Text(
                                    "Seu palpite salvo: ${registroDoJogo?.optInt("usuarioPlacarCasa")} x ${registroDoJogo?.optInt("usuarioPlacarFora")}",
                                    color = Color(0xFF81C784),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = {
                                    val casa = golsUsuarioCasa.toIntOrNull()
                                    val fora = golsUsuarioFora.toIntOrNull()
                                    if (casa == null || fora == null || casa !in 0..20 || fora !in 0..20) {
                                        erroHistorico = "Preencha os dois placares com números de 0 a 20."
                                    } else {
                                        salvandoPalpiteUsuario = true
                                        erroHistorico = ""
                                        HistoricoPalpitesNuvem.salvarPalpiteUsuario(
                                            uid, jogo, casa, fora
                                        ) { sucesso, mensagem ->
                                            salvandoPalpiteUsuario = false
                                            if (sucesso) carregarHistorico()
                                            else erroHistorico = mensagem ?: "Não foi possível salvar seu palpite."
                                        }
                                    }
                                },
                                enabled = !partidaBloqueada && !salvandoPalpiteUsuario &&
                                    uid.isNotBlank() && golsUsuarioCasa.isNotBlank() && golsUsuarioFora.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = corDestaque,
                                    disabledContainerColor = Color.DarkGray
                                )
                            ) {
                                Text(
                                    when {
                                        partidaBloqueada -> "🔒 PALPITE ENCERRADO"
                                        salvandoPalpiteUsuario -> "SALVANDO SEU PALPITE..."
                                        usuarioJaSalvou -> "ATUALIZAR MEU PALPITE"
                                        else -> "💾 SALVAR MEU PALPITE"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    salvandoPalpite = true
                                    erroHistorico = ""

                                    HistoricoPalpitesNuvem.salvar(
                                        uid,
                                        jogo,
                                        analise
                                    ) { sucesso, mensagem ->
                                        salvandoPalpite = false
                                        if (sucesso) {
                                            carregarHistorico()
                                        } else {
                                            erroHistorico = mensagem
                                                ?: "Não foi possível salvar o palpite."
                                        }
                                    }
                                },
                                enabled = !jaSalvo && !salvandoPalpite && uid.isNotBlank() && !partidaBloqueada,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = corDestaque,
                                    disabledContainerColor = Color.DarkGray
                                )
                            ) {
                                Text(
                                    when {
                                        partidaBloqueada && !jaSalvo -> "🔒 PRAZO ENCERRADO"
                                        jaSalvo -> "✓ PALPITE DO MOTOR SALVO"
                                        salvandoPalpite -> "SALVANDO..."
                                        else -> "💾 SALVAR PALPITE"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { carregarDados() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ATUALIZAR PALPITES")
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Divider(color = corDestaque.copy(alpha = 0.5f))

        Spacer(modifier = Modifier.height(15.dp))

        Text(
            "📊 MEU HISTÓRICO",
            color = corDestaque,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { carregarHistorico() },
            enabled = !historicoCarregando && uid.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (historicoCarregando) "CARREGANDO HISTÓRICO..." else "CARREGAR MEU HISTÓRICO")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (historicoCarregando) {
            Text(
                "Carregando histórico sob solicitação...",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }

        if (erroHistorico.isNotBlank()) {
            Text(
                erroHistorico,
                color = Color(0xFFFF8A80),
                fontSize = 12.sp
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {

                Text(
                    "PALPITES SALVOS: ${historico.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "JOGOS FINALIZADOS: ${finalizados.size}",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                Text(
                    "ACERTO DO RESULTADO (1X2): $acertos de ${finalizados.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Text(
                    "TAXA DE RESULTADO: $taxaAcerto%",
                    color = corDestaque,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "PLACAR EXATO: $acertosPlacar de ${finalizados.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Text(
                    "TAXA DE PLACAR EXATO: $taxaPlacar%",
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Divider(color = corDestaque.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "MEUS PALPITES: ${finalizadosUsuario.size} jogos finalizados",
                    color = corDestaque,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "ACERTO DO RESULTADO: $acertosUsuario de ${finalizadosUsuario.size} ($taxaUsuario%)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    "PLACAR EXATO: $acertosPlacarUsuario de ${finalizadosUsuario.size} ($taxaPlacarUsuario%)",
                    color = Color.White,
                    fontSize = 13.sp
                )

                Text(
                    "Resultado (1X2) considera apenas Casa, Empate ou Fora. Placar exato exige acertar os dois números.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("SIMULAÇÃO HISTÓRICA (SEPARADA)", color = corDestaque, fontWeight = FontWeight.Bold)
                Text(
                    "Partidas simuladas: ${simulados.size} • Acertos 1X2: $acertosSimulados • Taxa: $taxaSimulada%",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        simulandoHistorico = true
                        // A simulação histórica avalia somente as 380 partidas de 2025.
                        // Não inclui jogos de 2026 nem altera palpites reais do usuário.
                        HistoricoPalpitesNuvem.gerarSimulacaoHistorica(uid, jogosHistoricos.toList()) { quantidade, mensagem ->
                            simulandoHistorico = false
                            if (mensagem != null) {
                                erroHistorico = mensagem
                            } else {
                                carregarHistorico()
                                erroHistorico = "Simulação recalculada: $quantidade partidas atualizadas/adicionadas."
                            }
                        }
                    },
                    enabled = !simulandoHistorico && uid.isNotBlank() && jogosProntos &&
                        baseHistoricaPronta && jogosHistoricos.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (simulandoHistorico) "GERANDO SIMULAÇÃO..." else "GERAR SIMULAÇÃO HISTÓRICA")
                }
                Text(
                    "Executa somente partidas finalizadas ainda não registradas. Não altera seus palpites reais.",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { clipboardManager.setText(AnnotatedString(relatorioDiagnostico)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = corDestaque)
                    ) {
                        Text("📋 COPIAR RELATÓRIO COMPLETO", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("DIAGNÓSTICO DOS ERROS", color = corDestaque, fontWeight = FontWeight.Bold)
                Text("Acerto por resultado previsto", color = Color.White, fontSize = 12.sp)
                Text("Vitória da casa: ${taxaDoGrupo(simuladosCasa)}", color = Color.White, fontSize = 12.sp)
                Text("Empate: ${taxaDoGrupo(simuladosEmpate)}", color = Color.White, fontSize = 12.sp)
                Text("Vitória de fora: ${taxaDoGrupo(simuladosFora)}", color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("DISTRIBUIÇÃO DOS RESULTADOS REAIS", color = corDestaque, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text("Vitórias da casa: ${simuladosCasaReal.size} • Empates: ${simuladosEmpateReal.size} • Vitórias de fora: ${simuladosForaReal.size}", color = Color.White, fontSize = 12.sp)
                Text("Probabilidade média dada à casa quando ela venceu: ${"%.1f".format(java.util.Locale.US, mediaProbCasaEmVitoriasCasa)}%", color = Color.White, fontSize = 11.sp)
                Text("Probabilidade média de empate nos empates reais: ${"%.1f".format(java.util.Locale.US, mediaProbEmpateEmEmpates)}%", color = Color.White, fontSize = 11.sp)
                Text("Probabilidade média dada ao visitante quando ele venceu: ${"%.1f".format(java.util.Locale.US, mediaProbForaEmVitoriasFora)}%", color = Color.White, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Acerto por confiança máxima", color = Color.White, fontSize = 12.sp)
                Text("50% ou mais: ${taxaDoGrupo(simuladosAltaConf)}", color = Color.White, fontSize = 12.sp)
                Text("40% a 49%: ${taxaDoGrupo(simuladosMediaConf)}", color = Color.White, fontSize = 12.sp)
                Text("Abaixo de 40%: ${taxaDoGrupo(simuladosBaixaConf)}", color = Color.White, fontSize = 12.sp)
                Text(
                    "Esses recortes mostram onde o motor acerta e erra. Grupos pequenos ainda não permitem conclusões firmes.",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("TESTE PARALELO: DIXON-COLES", color = corDestaque, fontWeight = FontWeight.Bold)
                Text(
                    "Ajuste experimental dos placares baixos. Não altera os palpites oficiais.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                candidatosDixonColes.forEach { teste ->
                    val rho = teste.first
                    val quantidade = teste.second
                    val acertos = teste.third.first
                    val empatesEscolhidos = teste.third.second
                    val acertosEmpate = teste.third.third
                    val taxa = if (quantidade > 0) acertos * 100 / quantidade else 0
                    Text(
                        "ρ ${"%.2f".format(java.util.Locale.US, rho)}: $taxa% de acerto ($acertos/$quantidade) • empates previstos: $empatesEscolhidos • empates certos: $acertosEmpate",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
                Text(
                    "O melhor ajuste será escolhido por desempenho comparativo, não apenas por aumentar a quantidade de empates.",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("VALIDAÇÃO TEMPORAL (70% TREINO / 30% TESTE)", color = corDestaque, fontWeight = FontWeight.Bold)
                if (validacaoTemporalDisponivel) {
                    Text(
                        "Ajuste escolhido no bloco antigo: ρ ${"%.2f".format(java.util.Locale.US, rhoEscolhido)}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(
                        "Treino: ${treinoDixon.size} jogos • Teste futuro: ${testeDixon.size} jogos",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                    Text(
                        "Modelo original no teste: ${resultadoBaseTeste.first}/${resultadoBaseTeste.second} (${if (resultadoBaseTeste.second > 0) resultadoBaseTeste.first * 100 / resultadoBaseTeste.second else 0}%)",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(
                        "Modelo experimental no teste: ${resultadoAjustadoTeste.first}/${resultadoAjustadoTeste.second} (${if (resultadoAjustadoTeste.second > 0) resultadoAjustadoTeste.first * 100 / resultadoAjustadoTeste.second else 0}%)",
                        color = corDestaque,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        "Aguardando pelo menos 20 partidas com dados completos para separar treino e teste sem misturar períodos.",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("LABORATÓRIO: VANTAGEM DO MANDANTE", color = corDestaque, fontWeight = FontWeight.Bold)
                Text(
                    "Testa reduzir a média de gols esperados da casa. O fator é escolhido no treino e conferido em jogos posteriores; não altera os palpites oficiais.",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
                if (validacaoTemporalDisponivel) {
                    fatoresMandante.forEach { fator ->
                        val resultado = avaliarFatorMandante(treinoDixon, fator)
                        Text(
                            "Fator ${"%.2f".format(java.util.Locale.US, fator)}: ${resultado.first}/${treinoDixon.size} acertos no treino • empates: ${resultado.second} • vitórias fora: ${resultado.third}",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        "Melhor fator no treino: ${"%.2f".format(java.util.Locale.US, fatorMandanteEscolhido)}",
                        color = corDestaque,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        "Teste futuro, modelo atual: ${resultadoMandoBaseTeste.first}/${testeDixon.size} acertos • empates: ${resultadoMandoBaseTeste.second} • fora: ${resultadoMandoBaseTeste.third}",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                    Text(
                        "Teste futuro, fator ajustado: ${resultadoMandoAjustadoTeste.first}/${testeDixon.size} acertos • empates: ${resultadoMandoAjustadoTeste.second} • fora: ${resultadoMandoAjustadoTeste.third}",
                        color = corDestaque,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                } else {
                    Text("Aguardando amostra suficiente para treino e teste temporal.", color = Color.LightGray, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("LABORATÓRIO: DECISÃO DE EMPATE", color = corDestaque, fontWeight = FontWeight.Bold)
                Text(
                    "Testa quando aceitar o empate mesmo sem ele ser a maior probabilidade. O limiar é escolhido nos 70% jogos antigos e avaliado nos 30% seguintes. Não altera os palpites oficiais.",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
                if (validacaoTemporalDisponivel) {
                    limiaresEmpate.forEach { limiar ->
                        val resultado = avaliarLimiarEmpate(treinoDixon, limiar)
                        Text(
                            "Limiar ${"%.2f".format(java.util.Locale.US, limiar)}: ${resultado.first}/${treinoDixon.size} acertos • empates: ${resultado.second} • certos: ${resultado.third}",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                    Text("Melhor limiar no treino: ${"%.2f".format(java.util.Locale.US, limiarEmpateEscolhido)}", color = corDestaque, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Treino, modelo atual: ${resultadoEmpateBaseTreino.first}/${treinoDixon.size} • ajustado: ${resultadoEmpateAjustadoTreino.first}/${treinoDixon.size}", color = Color.White, fontSize = 11.sp)
                    Text("Teste futuro, modelo atual: ${resultadoEmpateBaseTeste.first}/${testeDixon.size} acertos • empates: ${resultadoEmpateBaseTeste.second} • certos: ${resultadoEmpateBaseTeste.third}", color = Color.White, fontSize = 11.sp)
                    Text("Teste futuro, limiar ajustado: ${resultadoEmpateAjustadoTeste.first}/${testeDixon.size} acertos • empates: ${resultadoEmpateAjustadoTeste.second} • certos: ${resultadoEmpateAjustadoTeste.third}", color = corDestaque, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("MÉTRICAS AVANÇADAS NO TESTE FUTURO", color = corDestaque, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Modelo original:\n$metricasBaseTeste", color = Color.White, fontSize = 10.sp)
                    Text("Limiar calibrado (${"%.2f".format(java.util.Locale.US, limiarEmpateEscolhido)}):\n$metricasAjustadasTeste", color = Color.White, fontSize = 10.sp)
                    Text("Seleção por Macro-F1 equilibra as três classes. Brier menor indica probabilidades melhores calibradas.", color = Color.LightGray, fontSize = 10.sp)
                } else {
                    Text("Aguardando amostra suficiente para treino e teste temporal.", color = Color.LightGray, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("RAIO-X DOS EMPATES", color = corDestaque, fontWeight = FontWeight.Bold)
                Text(
                    "Partidas analisadas: ${probabilidadesEmpate.size} de ${simulados.size}",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Empates escolhidos pelo motor: ${simuladosEmpate.size}",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Probabilidade média de empate: ${"%.1f".format(java.util.Locale.US, mediaProbEmpate)}%",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Maior probabilidade de empate: ${"%.1f".format(java.util.Locale.US, maiorProbEmpate)}%",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Partidas com dados de gols esperados: ${partidasComGolsEsperados.size} de ${simulados.size}",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Média de gols esperados: ${"%.2f".format(java.util.Locale.US, mediaGolsEsperadosCasa)} casa x ${"%.2f".format(java.util.Locale.US, mediaGolsEsperadosFora)} fora",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Diferença média entre os times: ${"%.2f".format(java.util.Locale.US, diferencaMediaGolsEsperados)} gol",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Jogos com gols esperados separados por até 0,25: $jogosComGolsProximos",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Empate ficou até 5 pontos do favorito: $empatesAteCincoPontos",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "Esses números mostram se o próprio cálculo de gols está criando desequilíbrio. Depois vamos calibrar com validação histórica, sem forçar resultados.",
                    color = Color.LightGray,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        historico.sortedByDescending {
            it.optString("data")
        }.forEach { palpite ->

            val finalizado = palpite.optBoolean("finalizado")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = corCartao
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {

                    Text(
                        "${palpite.optString("mandante")} x ${palpite.optString("visitante")}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        if (palpite.optString("origem") == "SIMULACAO_HISTORICA")
                            "SIMULAÇÃO RETROSPECTIVA"
                        else
                            "PALPITE REAL",
                        color = corDestaque,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Palpite: ${palpite.optInt("placarCasa")} x ${palpite.optInt("placarFora")}",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    if (finalizado) {
                        Text(
                            "Resultado real: ${palpite.optInt("golsReaisCasa")} x ${palpite.optInt("golsReaisFora")}",
                            color = Color.White,
                            fontSize = 12.sp
                        )

                        Text(
                            if (palpite.optBoolean("acertouResultado"))
                                "✓ Resultado acertado"
                            else
                                "✗ Resultado não acertado",
                            color = if (palpite.optBoolean("acertouResultado"))
                                corDestaque else Color.LightGray,
                            fontWeight = FontWeight.Bold
                        )

                        if (palpite.optBoolean("acertouPlacar")) {
                            Text(
                                "🏆 PLACAR EXATO!",
                                color = corDestaque,
                                fontWeight = FontWeight.Black
                            )
                        }
                    } else {
                        Text(
                            "Aguardando resultado",
                            color = corDestaque,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        if (historico.isEmpty()) {
            Text(
                "Seus palpites salvos aparecerão aqui.",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }
    }
}
