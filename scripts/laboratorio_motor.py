#!/usr/bin/env python3
"""Laboratório independente do motor 2.0. Não altera o motor oficial nem dados do usuário."""
from __future__ import annotations

import json
import math
import os
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

BASE_URL = os.environ.get(
    "BRASILEIRAO_API_URL",
    "https://cold-sbrasileirao-predictor-apiilence-07ef.jrnischjr.workers.dev/",
)
SEASON = int(os.environ.get("SEASON", "2025"))
OUTPUT = Path("laboratorio-output")
FINISHED = {"FINISHED", "FINALIZADO", "AWARDED"}
CLASSES = ("casa", "empate", "fora")


@dataclass(frozen=True)
class Match:
    date: str
    home: str
    away: str
    hg: int
    ag: int
    round: int | None = None


def fetch_matches() -> list[dict]:
    url = BASE_URL + ("&" if "?" in BASE_URL else "?") + urllib.parse.urlencode({"endpoint": "fd-history"})
    request = urllib.request.Request(url, headers={"User-Agent": "BrasileiraoPredictor-Lab/2.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    rows = payload.get("jogos") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        raise ValueError("A API não retornou a lista esperada em 'jogos'.")
    return rows


def normalize(rows: list[dict]) -> list[Match]:
    matches = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        season = row.get("temporada")
        date = str(row.get("data") or "")[:10]
        if not date or not date[:4].isdigit():
            continue
        if int(season or date[:4]) != SEASON:
            continue
        if str(row.get("situacao") or "").upper() not in FINISHED:
            continue
        home = row.get("mandante") or {}
        away = row.get("visitante") or {}
        score = row.get("placar") or {}
        if not isinstance(home, dict) or not isinstance(away, dict) or not isinstance(score, dict):
            continue
        hn, an = str(home.get("nome") or "").strip(), str(away.get("nome") or "").strip()
        try:
            hg, ag = int(score["mandante"]), int(score["visitante"])
        except (KeyError, TypeError, ValueError):
            continue
        if hn and an and hg >= 0 and ag >= 0:
            matches.append(Match(date, hn, an, hg, ag, row.get("rodada")))
    return sorted(matches, key=lambda m: (m.date, m.home, m.away))


def poisson(mean: float, goals: int) -> float:
    value = math.exp(-mean)
    for i in range(1, goals + 1):
        value *= mean / i
    return value


def normalize_probs(values: tuple[float, float, float]) -> tuple[float, float, float]:
    total = sum(values)
    if not math.isfinite(total) or total <= 0:
        return (1 / 3, 1 / 3, 1 / 3)
    return tuple(max(0.0, x) / total for x in values)


def elo_prob(match: Match, history: list[Match]) -> tuple[float, float, float]:
    ratings: dict[str, float] = {}
    draws = 0
    for old in history:
        h, a = old.home.casefold(), old.away.casefold()
        rh, ra = ratings.get(h, 1500.0), ratings.get(a, 1500.0)
        expected = 1.0 / (1.0 + 10.0 ** (-(rh + 65.0 - ra) / 400.0))
        actual = 1.0 if old.hg > old.ag else 0.5 if old.hg == old.ag else 0.0
        change = 20.0 * (actual - expected)
        ratings[h], ratings[a] = rh + change, ra - change
        draws += int(old.hg == old.ag)
    total = len(history)
    draw_rate = (draws + 5.4) / (total + 20.0)
    rh = ratings.get(match.home.casefold(), 1500.0)
    ra = ratings.get(match.away.casefold(), 1500.0)
    expected_home = 1.0 / (1.0 + 10.0 ** (-(rh + 65.0 - ra) / 400.0))
    decisive_home = min(1.0, max(0.0, (expected_home - draw_rate / 2.0) / (1.0 - draw_rate)))
    return normalize_probs(((1 - draw_rate) * decisive_home, draw_rate,
                            (1 - draw_rate) * (1 - decisive_home)))


def poisson_prob(match: Match, history: list[Match]) -> tuple[float, float, float]:
    league_home = sum(m.hg for m in history) / len(history) if history else 1.35
    league_away = sum(m.ag for m in history) / len(history) if history else 1.10
    league_home, league_away = max(0.2, league_home), max(0.2, league_away)

    home_home = [m for m in history if m.home.casefold() == match.home.casefold()][-8:]
    away_away = [m for m in history if m.away.casefold() == match.away.casefold()][-8:]

    def smoothed(values: list[int], reference: float) -> float:
        observed = sum(values) / len(values) if values else reference
        reliability = len(values) / (len(values) + 4.0)
        return reference + (observed - reference) * reliability

    home_attack = smoothed([m.hg for m in home_home], league_home)
    home_defence = smoothed([m.ag for m in home_home], league_away)
    away_attack = smoothed([m.ag for m in away_away], league_away)
    away_defence = smoothed([m.hg for m in away_away], league_home)

    expected_home = league_home * math.sqrt(
        max(0.12, home_attack / league_home) * max(0.12, away_defence / league_home)
    )
    expected_away = league_away * math.sqrt(
        max(0.12, away_attack / league_away) * max(0.12, home_defence / league_away)
    )
    expected_home = min(3.8, max(0.15, expected_home))
    expected_away = min(3.8, max(0.15, expected_away))

    home = draw = away = 0.0
    for hg in range(11):
        for ag in range(11):
            chance = poisson(expected_home, hg) * poisson(expected_away, ag)
            if hg > ag:
                home += chance
            elif hg == ag:
                draw += chance
            else:
                away += chance
    return normalize_probs((home, draw, away))


def predict(match: Match, all_matches: list[Match]) -> dict:
    # Regra conservadora: nenhuma partida da mesma data entra no contexto.
    history = [m for m in all_matches if m.date < match.date]
    elo = elo_prob(match, history)
    pois = poisson_prob(match, history)
    blend = normalize_probs(tuple((a + b) / 2 for a, b in zip(elo, pois)))
    actual = 0 if match.hg > match.ag else 1 if match.hg == match.ag else 2
    return {
        "actual": actual,
        "elo": elo,
        "poisson": pois,
        "blend": blend,
        "date": match.date,
        "home": match.home,
        "away": match.away,
    }


def argmax(values: tuple[float, float, float]) -> int:
    return max(range(3), key=lambda i: values[i])


def score_metrics(rows: list[dict], model: str, draw_ratio: float | None = None) -> dict:
    if not rows:
        return {"games": 0, "accuracy": 0.0, "brier": 0.0, "macro_f1": 0.0}
    matrix = [[0, 0, 0] for _ in range(3)]
    brier = 0.0
    correct = 0
    for row in rows:
        probs = row[model]
        pred = argmax(probs)
        if draw_ratio is not None and probs[1] >= draw_ratio * max(probs[0], probs[2]):
            pred = 1
        actual = row["actual"]
        matrix[actual][pred] += 1
        correct += int(pred == actual)
        brier += sum((probs[i] - (1.0 if i == actual else 0.0)) ** 2 for i in range(3))
    f1s, per_class = [], {}
    for i, name in enumerate(CLASSES):
        tp = matrix[i][i]
        fp = sum(matrix[r][i] for r in range(3) if r != i)
        fn = sum(matrix[i][c] for c in range(3) if c != i)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        f1s.append(f1)
        per_class[name] = {"precision": precision, "recall": recall, "f1": f1,
                           "predicted": sum(matrix[r][i] for r in range(3)),
                           "correct": tp}
    return {
        "games": len(rows),
        "accuracy": correct / len(rows),
        "brier": brier / len(rows),
        "macro_f1": sum(f1s) / 3,
        "per_class": per_class,
        "confusion_matrix": matrix,
    }


def best_draw_threshold(train: list[dict]) -> tuple[float, dict]:
    candidates = [round(x / 100, 2) for x in range(50, 101, 5)]
    results = [(score_metrics(train, "blend", t)["macro_f1"], t,
                score_metrics(train, "blend", t)) for t in candidates]
    # Empate de métricas: prefere o limiar mais alto, que é menos agressivo.
    best = max(results, key=lambda item: (item[0], item[1]))
    return best[1], best[2]


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def main() -> None:
    raw = fetch_matches()
    matches = normalize(raw)
    if len(matches) < 100:
        raise ValueError(f"Base insuficiente para laboratório: {len(matches)} jogos válidos em {SEASON}.")
    dates = sorted({match.date for match in matches})
    split_date_index = int(len(dates) * 0.70)
    if split_date_index <= 1 or len(dates) - split_date_index < 2:
        raise ValueError("Amostra insuficiente para validação temporal 70/30.")
    split_date = dates[split_date_index]

    predictions = [predict(match, matches) for match in matches]
    # O corte é por data, nunca por linha: partidas do mesmo dia não
    # podem cair simultaneamente em treino e teste.
    train = [row for row in predictions if row["date"] < split_date]
    test = [row for row in predictions if row["date"] >= split_date]
    if len(train) < 20 or len(test) < 20:
        raise ValueError("Amostra insuficiente após separar treino/teste por data.")
    threshold, train_threshold_metrics = best_draw_threshold(train)

    models = {}
    for model in ("elo", "poisson", "blend"):
        models[model] = {
            "train": score_metrics(train, model),
            "test": score_metrics(test, model),
        }
    models["blend_contextual_draw"] = {
        "train": train_threshold_metrics,
        "test": score_metrics(test, "blend", threshold),
        "draw_ratio_threshold": threshold,
    }

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "season": SEASON,
        "source": "Football-Data API via Worker fd-history",
        "historical_games": len(matches),
        "train_games": len(train),
        "test_games": len(test),
        "test_start_date": split_date,
        "same_day_excluded": True,
        "train_test_split_by_date": True,
        "official_engine_changed": False,
        "models": models,
        "notes": [
            "Laboratório independente; nenhum resultado altera o motor oficial.",
            "Cada previsão usa somente partidas finalizadas em datas anteriores à partida-alvo.",
            "Ajuste do detector contextual é escolhido exclusivamente no bloco de treino.",
            "Brier é a soma dos erros quadráticos das três classes, média por partida; menor é melhor.",
            "Este laboratório é uma aproximação Python independente e não substitui a validação do Kotlin em produção."
        ],
    }
    OUTPUT.mkdir(exist_ok=True)
    (OUTPUT / "resultado.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        f"# Laboratório do motor 2.0 | Brasileirão {SEASON}",
        "",
        f"- Gerado em: {report['generated_at']}",
        f"- Jogos válidos: **{len(matches)}**",
        f"- Treino cronológico (70%): **{len(train)}**",
        f"- Teste futuro (aprox. 30%): **{len(test)}** (a partir de {split_date})",
        "- Partidas do mesmo dia excluídas do contexto para evitar vazamento.",
        "- Motor oficial preservado: **sim**.",
        "",
        "## Comparação no teste futuro",
        "",
        "| Modelo | Acerto | Macro-F1 | Brier | Empates previstos | Empates certos |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for key, label in (("elo", "Elo"), ("poisson", "Poisson"), ("blend", "Combinação 50/50")):
        m = models[key]["test"]
        d = m["per_class"]["empate"]
        lines.append(f"| {label} | {pct(m['accuracy'])} ({m['games'] and round(m['accuracy']*m['games'])}/{m['games']}) | {pct(m['macro_f1'])} | {m['brier']:.4f} | {d['predicted']} | {d['correct']} |")
    contextual = models["blend_contextual_draw"]["test"]
    d = contextual["per_class"]["empate"]
    lines += [
        f"| Combinação + empate contextual ({threshold:.2f}) | {pct(contextual['accuracy'])} ({round(contextual['accuracy']*contextual['games'])}/{contextual['games']}) | {pct(contextual['macro_f1'])} | {contextual['brier']:.4f} | {d['predicted']} | {d['correct']} |",
        "",
        "## Métricas por classe no teste",
        "",
    ]
    for key, label in (("blend", "Combinação 50/50"), ("blend_contextual_draw", "Combinação + empate contextual")):
        metrics = models[key]["test"]
        lines += [f"### {label}", "", "| Resultado | Precisão | Recall | F1 | Previsões | Acertos |", "|---|---:|---:|---:|---:|---:|"]
        for name in CLASSES:
            c = metrics["per_class"][name]
            lines.append(f"| {name.title()} | {pct(c['precision'])} | {pct(c['recall'])} | {pct(c['f1'])} | {c['predicted']} | {c['correct']} |")
        lines.append("")
    lines += [
        "## Decisão automática",
        "",
        "Este relatório é experimental. Nenhum modelo é promovido automaticamente. A substituição do motor oficial exige repetição em outras janelas/temporadas e validação no mesmo conjunto de jogos usado pelo aplicativo.",
        "",
        "## Limitações",
        "",
        *[f"- {note}" for note in report["notes"]],
        "",
    ]
    (OUTPUT / "relatorio.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:25]))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERRO NO LABORATÓRIO: {exc}", file=sys.stderr)
        raise
