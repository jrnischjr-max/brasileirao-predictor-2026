
package br.com.jrnischjr.brasileiraopredictor

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AnalisePalpite(
    val probCasa: Int,
    val probEmpate: Int,
    val probFora: Int,
    val placarCasa: Int,
    val placarFora: Int,
    val formaCasa: String,
    val formaFora: String
)

object MotorPalpites {

    private data class Partida(
        val casa: String,
        val fora: String,
        val golsCasa: Int,
        val golsFora: Int,
        val data: String
    )

    private fun partidasFinalizadas(
        jogos: List<JSONObject>
    ): List<Partida> {
        return jogos.mapNotNull { jogo ->
            val situacao = jogo.optString("situacao").uppercase()

            if (situacao !in listOf(
                    "FINISHED",
                    "FINALIZADO",
                    "AWARDED"
                )
            ) return@mapNotNull null

            val mandante = jogo.optJSONObject("mandante")
                ?.optString("nome", "") ?: ""

            val visitante = jogo.optJSONObject("visitante")
                ?.optString("nome", "") ?: ""

            val placar = jogo.optJSONObject("placar")
                ?: return@mapNotNull null

            val golsCasa = placar.optInt("mandante", -1)
            val golsFora = placar.optInt("visitante", -1)

            if (mandante.isBlank() || visitante.isBlank() ||
                golsCasa < 0 || golsFora < 0
            ) return@mapNotNull null

            Partida(
                mandante,
                visitante,
                golsCasa,
                golsFora,
                jogo.optString("data")
            )
        }.sortedBy { it.data }
    }

    private fun poisson(
        media: Double,
        gols: Int
    ): Double {
        var resultado = exp(-media)

        for (i in 1..gols) {
            resultado *= media / i
        }

        return resultado
    }

    private fun partidasDoTime(
        nome: String,
        partidas: List<Partida>
    ): List<Partida> {
        return partidas.filter {
            it.casa.equals(nome, true) ||
            it.fora.equals(nome, true)
        }
    }

    private fun formaRecente(
        nome: String,
        partidas: List<Partida>,
        clubes: List<JSONObject>
    ): Pair<Double, String> {

        val recentes = partidasDoTime(nome, partidas)
            .takeLast(5)
            .reversed()

        if (recentes.isEmpty()) {
            return 1.0 to "Sem histórico"
        }

        val mediaPontosLiga = clubes.mapNotNull {
            val jogos = it.optInt("jogos")
            if (jogos > 0) it.optDouble("pontos") / jogos
            else null
        }.average().takeIf { it.isFinite() && it > 0 }
            ?: 1.5

        var pontosPonderados = 0.0
        var pesoTotal = 0.0
        var saldoGols = 0.0
        var forma = ""

        recentes.forEachIndexed { indice, partida ->
            val peso = (5 - indice).toDouble()

            val emCasa = partida.casa.equals(nome, true)

            val golsFeitos = if (emCasa)
                partida.golsCasa else partida.golsFora

            val golsSofridos = if (emCasa)
                partida.golsFora else partida.golsCasa

            val pontos = when {
                golsFeitos > golsSofridos -> 3.0
                golsFeitos == golsSofridos -> 1.0
                else -> 0.0
            }

            val adversario = if (emCasa)
                partida.fora else partida.casa

            val dadosAdversario = clubes.firstOrNull {
                it.optString("nome").equals(adversario, true)
            }

            val jogosAdversario =
                dadosAdversario?.optInt("jogos") ?: 0

            val forcaAdversario = if (jogosAdversario > 0) {
                dadosAdversario!!.optDouble("pontos") /
                    jogosAdversario
            } else {
                mediaPontosLiga
            }

            val ajusteAdversario = (
                0.85 + 0.15 *
                    forcaAdversario / mediaPontosLiga
            ).coerceIn(0.85, 1.15)

            pontosPonderados += pontos * ajusteAdversario * peso
            pesoTotal += peso

            saldoGols += (golsFeitos - golsSofridos)

            forma += when {
                pontos == 3.0 -> "V"
                pontos == 1.0 -> "E"
                else -> "D"
            }
        }

        val pontosMedios = pontosPonderados / pesoTotal
        val saldoMedio = saldoGols / recentes.size

        val fator = (
            1.0 +
            (pontosMedios - 1.5) * 0.07 +
            saldoMedio * 0.035
        ).coerceIn(0.85, 1.15)

        return fator to forma
    }

    private fun fatorConfrontoDireto(
        nomeCasa: String,
        nomeFora: String,
        partidas: List<Partida>
    ): Double {
        val confrontos = partidas.filter { partida ->
            (partida.casa.equals(nomeCasa, true) &&
                partida.fora.equals(nomeFora, true)) ||
            (partida.casa.equals(nomeFora, true) &&
                partida.fora.equals(nomeCasa, true))
        }.takeLast(5)

        if (confrontos.isEmpty()) return 1.0

        var saldoPonderado = 0.0
        var pesoTotal = 0.0

        confrontos.forEachIndexed { indice, partida ->
            val peso = (indice + 1).toDouble()
            val casaEraMandante = partida.casa.equals(nomeCasa, true)
            val golsTimeCasa = if (casaEraMandante) partida.golsCasa else partida.golsFora
            val golsTimeFora = if (casaEraMandante) partida.golsFora else partida.golsCasa

            val resultado = when {
                golsTimeCasa > golsTimeFora -> 1.0
                golsTimeCasa < golsTimeFora -> -1.0
                else -> 0.0
            }

            saldoPonderado += resultado * peso
            pesoTotal += peso
        }

        val equilibrio = if (pesoTotal > 0.0) saldoPonderado / pesoTotal else 0.0
        return (1.0 + equilibrio * 0.035).coerceIn(0.965, 1.035)
    }

    private fun media(
        valores: List<Double>,
        padrao: Double
    ): Double {
        return valores.average()
            .takeIf { valores.isNotEmpty() && it.isFinite() }
            ?: padrao
    }

    // Reconstitui o contexto disponível antes da partida. Jogos do mesmo dia
    // são excluídos para evitar usar resultados que talvez tenham ocorrido depois.
    fun analisarHistorico(
        jogo: JSONObject,
        todosJogos: List<JSONObject>
    ): AnalisePalpite? {
        val dataAlvo = jogo.optString("data").take(10)
        if (dataAlvo.isBlank()) return null

        val anteriores = todosJogos.filter { partida ->
            val dataPartida = partida.optString("data").take(10)
            dataPartida.isNotBlank() && dataPartida < dataAlvo
        }

        val nomes = todosJogos.flatMap { partida ->
            listOf(
                partida.optJSONObject("mandante")?.optString("nome", "").orEmpty(),
                partida.optJSONObject("visitante")?.optString("nome", "").orEmpty()
            )
        }.filter { it.isNotBlank() }.distinct()

        val estatisticas = nomes.map { nome ->
            var jogosTime = 0
            var pontosTime = 0
            anteriores.forEach { partida ->
                val situacao = partida.optString("situacao").uppercase()
                val placar = partida.optJSONObject("placar")
                val casa = partida.optJSONObject("mandante")?.optString("nome", "").orEmpty()
                val fora = partida.optJSONObject("visitante")?.optString("nome", "").orEmpty()
                val gc = placar?.optInt("mandante", -1) ?: -1
                val gf = placar?.optInt("visitante", -1) ?: -1
                if (situacao in listOf("FINISHED", "FINALIZADO", "AWARDED") &&
                    gc >= 0 && gf >= 0 && (casa.equals(nome, true) || fora.equals(nome, true))
                ) {
                    jogosTime++
                    pontosTime += when {
                        casa.equals(nome, true) && gc > gf -> 3
                        fora.equals(nome, true) && gf > gc -> 3
                        gc == gf -> 1
                        else -> 0
                    }
                }
            }
            JSONObject().put("nome", nome).put("jogos", jogosTime).put("pontos", pontosTime)
        }

        return analisar(jogo, anteriores, estatisticas)
    }

    fun analisar(
        jogo: JSONObject,
        todosJogos: List<JSONObject>,
        clubes: List<JSONObject>
    ): AnalisePalpite? {

        val nomeCasa = jogo.optJSONObject("mandante")
            ?.optString("nome", "") ?: ""

        val nomeFora = jogo.optJSONObject("visitante")
            ?.optString("nome", "") ?: ""

        if (nomeCasa.isBlank() || nomeFora.isBlank()) {
            return null
        }

        val timeCasa = clubes.firstOrNull {
            it.optString("nome").equals(nomeCasa, true)
        } ?: return null

        val timeFora = clubes.firstOrNull {
            it.optString("nome").equals(nomeFora, true)
        } ?: return null

        val partidas = partidasFinalizadas(todosJogos)

        if (partidas.isEmpty()) return null

        val jogosCasa = partidasDoTime(nomeCasa, partidas)
            .filter { it.casa.equals(nomeCasa, true) }

        val jogosFora = partidasDoTime(nomeFora, partidas)
            .filter { it.fora.equals(nomeFora, true) }

        val jogosLigaCasa = partidas.map { it.golsCasa }
        val jogosLigaFora = partidas.map { it.golsFora }

        val mediaLigaCasa = media(
            jogosLigaCasa.map { it.toDouble() },
            1.35
        )

        val mediaLigaFora = media(
            jogosLigaFora.map { it.toDouble() },
            1.10
        )

        fun golsPorJogo(
            lista: List<Partida>,
            nome: String,
            feitos: Boolean
        ): Double {
            if (lista.isEmpty()) return 0.0

            val recentes = lista.takeLast(8)
            var somaPonderada = 0.0
            var pesoTotal = 0.0

            recentes.forEachIndexed { indice, partida ->
                val gols = when {
                    partida.casa.equals(nome, true) ->
                        if (feitos) partida.golsCasa else partida.golsFora
                    else ->
                        if (feitos) partida.golsFora else partida.golsCasa
                }
                val peso = (indice + 1).toDouble()
                somaPonderada += gols * peso
                pesoTotal += peso
            }

            return if (pesoTotal > 0.0) somaPonderada / pesoTotal else 0.0
        }

        // Amostras pequenas recebem ajuste em direção à média da liga.
        // Isso evita que um ou dois jogos distorçam a previsão.
        fun ajustarPelaAmostra(
            observado: Double,
            referencia: Double,
            quantidade: Int
        ): Double {
            val confiabilidade = quantidade.toDouble() / (quantidade + 4.0)
            return referencia + (observado - referencia) * confiabilidade
        }

        val ataqueCasaBruto = if (jogosCasa.isNotEmpty())
            golsPorJogo(jogosCasa, nomeCasa, true)
        else mediaLigaCasa

        val defesaCasaBruta = if (jogosCasa.isNotEmpty())
            golsPorJogo(jogosCasa, nomeCasa, false)
        else mediaLigaFora

        val ataqueForaBruto = if (jogosFora.isNotEmpty())
            golsPorJogo(jogosFora, nomeFora, true)
        else mediaLigaFora

        val defesaForaBruta = if (jogosFora.isNotEmpty())
            golsPorJogo(jogosFora, nomeFora, false)
        else mediaLigaCasa

        val ataqueCasa = ajustarPelaAmostra(ataqueCasaBruto, mediaLigaCasa, jogosCasa.size)
        val defesaCasa = ajustarPelaAmostra(defesaCasaBruta, mediaLigaFora, jogosCasa.size)
        val ataqueFora = ajustarPelaAmostra(ataqueForaBruto, mediaLigaFora, jogosFora.size)
        val defesaFora = ajustarPelaAmostra(defesaForaBruta, mediaLigaCasa, jogosFora.size)

        val fatorAtaqueCasa =
            (ataqueCasa / mediaLigaCasa).coerceIn(0.35, 2.5)

        val fatorDefesaFora =
            (defesaFora / mediaLigaCasa).coerceIn(0.35, 2.5)

        val fatorAtaqueFora =
            (ataqueFora / mediaLigaFora).coerceIn(0.35, 2.5)

        val fatorDefesaCasa =
            (defesaCasa / mediaLigaFora).coerceIn(0.35, 2.5)

        val (formaFatorCasa, formaCasa) =
            formaRecente(nomeCasa, partidas, clubes)

        val (formaFatorFora, formaFora) =
            formaRecente(nomeFora, partidas, clubes)

        val fatorConfronto = fatorConfrontoDireto(
            nomeCasa,
            nomeFora,
            partidas
        )

        val esperadoCasa = (
            mediaLigaCasa *
            sqrt(fatorAtaqueCasa * fatorDefesaFora) *
            formaFatorCasa *
            fatorConfronto *
            1.08
        ).coerceIn(0.15, 3.8)

        val esperadoFora = (
            mediaLigaFora *
            sqrt(fatorAtaqueFora * fatorDefesaCasa) *
            formaFatorFora *
            (2.0 - fatorConfronto) *
            0.94
        ).coerceIn(0.15, 3.8)

        var vitoriaCasa = 0.0
        var empate = 0.0
        var vitoriaFora = 0.0

        for (gCasa in 0..8) {
            for (gFora in 0..8) {
                val chance =
                    poisson(esperadoCasa, gCasa) *
                    poisson(esperadoFora, gFora)

                when {
                    gCasa > gFora -> vitoriaCasa += chance
                    gCasa == gFora -> empate += chance
                    else -> vitoriaFora += chance
                }
            }
        }

        val total = vitoriaCasa + empate + vitoriaFora

        if (total <= 0.0) return null

        val pCasa =
            (vitoriaCasa / total * 100).roundToInt()

        val pEmpate =
            (empate / total * 100).roundToInt()

        val pFora = 100 - pCasa - pEmpate

        return AnalisePalpite(
            probCasa = pCasa,
            probEmpate = pEmpate,
            probFora = pFora,
            placarCasa = esperadoCasa.roundToInt(),
            placarFora = esperadoFora.roundToInt(),
            formaCasa = formaCasa,
            formaFora = formaFora
        )
    }
}
