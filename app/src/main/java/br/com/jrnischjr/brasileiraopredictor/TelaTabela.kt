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
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "POS",
                    color = corDestaque,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(32.dp)
                )

                Text(
                    "CLUBE",
                    color = corDestaque,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    "PTS",
                    color = corDestaque,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(32.dp)
                )

                Text(
                    "J",
                    color = corDestaque,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp)
                )

                Text(
                    "SG",
                    color = corDestaque,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(28.dp)
                )
            }

            clubes.forEach { clube ->
                val posicao = clube.optInt("posicao")
                val nome = clube.optString("nome", "Clube")
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${posicao}º",
                                color = corDestaque,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                modifier = Modifier.width(32.dp)
                            )

                            Text(
                                nome,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                "$pontos",
                                color = corDestaque,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                modifier = Modifier.width(32.dp)
                            )

                            Text(
                                "$jogos",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.width(24.dp)
                            )

                            Text(
                                "$saldo",
                                color = if (saldo > 0) Color(0xFF4ADE80)
                                    else if (saldo < 0) Color(0xFFFF6B6B)
                                    else Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.width(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            "V $vitorias   E $empates   D $derrotas   GP $golsPro   GC $golsContra",
                            color = Color.LightGray,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
