package br.com.jrnischjr.brasileiraopredictor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
fun TelaTabela(
    corDestaque: Color,
    corCartao: Color
) {
    val clubes = remember {
        mutableStateListOf<JSONObject>()
    }

    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf("") }

    fun carregarTabela() {
        carregando = true
        erro = ""

        DadosBrasileirao.buscarClassificacao { dados, mensagem ->
            carregando = false

            if (dados != null) {
                val lista = dados.optJSONArray("classificacao")
                clubes.clear()

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        val clube = lista.optJSONObject(i)
                        if (clube != null) {
                            clubes.add(clube)
                        }
                    }
                }

                if (clubes.isEmpty()) {
                    erro = "Nenhuma classificação encontrada."
                }
            } else {
                erro = mensagem ?: "Falha ao carregar a classificação."
            }
        }
    }

    LaunchedEffect(Unit) {
        if (clubes.isEmpty() && !carregando) {
            carregarTabela()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "🏆 CLASSIFICAÇÃO BRASILEIRÃO 2026",
            color = corDestaque,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Tabela oficial • 20 clubes",
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (carregando) {
            CircularProgressIndicator(color = corDestaque)
            Text(
                "Atualizando classificação...",
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (erro.isNotEmpty()) {
            Text(
                erro,
                color = Color.White,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(onClick = { carregarTabela() }) {
                Text("TENTAR NOVAMENTE")
            }
        }

        if (clubes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(corCartao)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("POS", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp), maxLines = 1)
                Text("CLUBE", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Text("PTS", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(27.dp), maxLines = 1)
                Text("J", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp), maxLines = 1)
                Text("SG", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp), maxLines = 1)
                Text("V", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp), maxLines = 1)
                Text("E", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp), maxLines = 1)
                Text("D", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp), maxLines = 1)
                Text("GP", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp), maxLines = 1)
                Text("GC", color = corDestaque, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp), maxLines = 1)
            }

            clubes.forEach { clube ->
                val posicao = clube.optInt("posicao")
                val nome = nomeExibicaoClube(clube.optString("nome", "Clube"))
                val escudo = clube.optString("escudo").takeIf { it.isNotBlank() }
                val pontos = clube.optInt("pontos")
                val jogos = clube.optInt("jogos")
                val vitorias = clube.optInt("vitorias")
                val empates = clube.optInt("empates")
                val derrotas = clube.optInt("derrotas")
                val saldo = clube.optInt("saldo")
                val golsPro = clube.optInt("golsPro")
                val golsContra = clube.optInt("golsContra")

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = corCartao
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${posicao}º",
                            color = corDestaque,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            modifier = Modifier.width(28.dp),
                            maxLines = 1
                        )

                        EscudoClube(
                            url = escudo,
                            nome = nome,
                            tamanho = 22.dp
                        )

                        Text(
                            nome,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                            maxLines = 1,
                            softWrap = false
                        )

                        Text("$pontos", color = corDestaque, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.width(27.dp), maxLines = 1)
                        Text("$jogos", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(18.dp), maxLines = 1)
                        Text("$saldo", color = if (saldo > 0) Color(0xFF4ADE80) else if (saldo < 0) Color(0xFFFF6B6B) else Color.White, fontSize = 10.sp, modifier = Modifier.width(22.dp), maxLines = 1)
                        Text("$vitorias", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(18.dp), maxLines = 1)
                        Text("$empates", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(18.dp), maxLines = 1)
                        Text("$derrotas", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(18.dp), maxLines = 1)
                        Text("$golsPro", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(22.dp), maxLines = 1)
                        Text("$golsContra", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(22.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}
