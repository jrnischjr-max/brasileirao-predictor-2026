#!/usr/bin/env python3
"""Calcula e publica o Top 10 do Brasileirão 2026 no Firestore."""
from __future__ import annotations

import json
import os
import hashlib
import sys
import unicodedata
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone

import firebase_admin
from firebase_admin import credentials, firestore

API_URL = os.environ.get(
    "BRASILEIRAO_API_URL",
    "https://cold-sbrasileirao-predictor-apiilence-07ef.jrnischjr.workers.dev/",
)
SEASON = 2026
FINISHED = {"FINISHED", "FINALIZADO", "AWARDED"}


def normalize(value: object) -> str:
    raw = unicodedata.normalize("NFKD", str(value or ""))
    return "".join(ch for ch in raw if not unicodedata.combining(ch)).strip().casefold()


def match_key(row: dict) -> tuple[str, str, str]:
    home = (row.get("mandante") or {}).get("nome", "")
    away = (row.get("visitante") or {}).get("nome", "")
    date = str(row.get("data") or "")[:10]
    return date, normalize(home), normalize(away)


def fetch_matches() -> list[dict]:
    url = API_URL + ("&" if "?" in API_URL else "?") + urllib.parse.urlencode(
        {"endpoint": "fd-matches"}
    )
    request = urllib.request.Request(
        url, headers={"User-Agent": "BrasileiraoPredictor-Ranking/1.0"}
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        payload = json.loads(response.read().decode("utf-8"))
    rows = payload.get("jogos") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        raise ValueError("API não retornou a lista 'jogos' esperada.")
    return rows


def outcome(home: int, away: int) -> str:
    if home > away:
        return "CASA"
    if home < away:
        return "FORA"
    return "EMPATE"


def main() -> None:
    raw_service_account = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw_service_account:
        raise RuntimeError(
            "Configure o secret FIREBASE_SERVICE_ACCOUNT_JSON no GitHub antes de executar."
        )
    service_account = json.loads(raw_service_account)
    if not firebase_admin._apps:
        firebase_admin.initialize_app(credentials.Certificate(service_account))
    db = firestore.client()

    rows = fetch_matches()
    completed_by_id: dict[str, dict] = {}
    completed_by_key: dict[tuple[str, str, str], dict] = {}
    round_totals: dict[int, int] = defaultdict(int)
    round_finished: dict[int, int] = defaultdict(int)
    for row in rows:
        if not isinstance(row, dict):
            continue
        season = row.get("temporada")
        date = str(row.get("data") or "")[:10]
        if int(season or (date[:4] if date[:4].isdigit() else 0)) != SEASON:
            continue
        try:
            round_number = int(row.get("rodada") or row.get("round") or 0)
        except (TypeError, ValueError):
            round_number = 0
        if round_number > 0:
            round_totals[round_number] += 1
        if str(row.get("situacao") or "").upper() not in FINISHED:
            continue
        score = row.get("placar") or {}
        try:
            hg, ag = int(score["mandante"]), int(score["visitante"])
        except (KeyError, TypeError, ValueError):
            continue
        if hg < 0 or ag < 0:
            continue
        row["_score"] = (hg, ag)
        row["_round"] = round_number
        if round_number > 0:
            round_finished[round_number] += 1
        game_id = str(row.get("id") or "").strip()
        if game_id:
            completed_by_id[game_id] = row
        completed_by_key[match_key(row)] = row

    if not completed_by_id and not completed_by_key:
        raise RuntimeError("Nenhuma partida finalizada de 2026 foi encontrada; ranking anterior preservado.")

    leaderboard = []
    evaluated_matches = set()
    profeta_predictions: dict[str, tuple[dict, int, int]] = {}
    # Estatísticas cumulativas por rodada para comparar posições entre rodadas.
    round_stats_by_person: dict[str, dict] = {}
    for user_doc in db.collection("users").stream():
        uid = user_doc.id
        profile = user_doc.to_dict() or {}
        nickname = str(profile.get("name") or "").strip()
        if not nickname:
            nickname = "Torcedor"

        points = exact = correct = evaluated = 0
        participant_id = hashlib.sha256(f"user:{uid}".encode("utf-8")).hexdigest()[:16]
        person_rounds = round_stats_by_person.setdefault(participant_id, {
            "nickname": nickname[:40],
            "rounds": defaultdict(lambda: {"points": 0, "correct": 0, "exactScores": 0, "evaluated": 0}),
        })
        predictions = db.collection("users").document(uid).collection(
            "historicoPalpites"
        ).stream()

        for prediction_doc in predictions:
            prediction = prediction_doc.to_dict() or {}
            # Simulações retrospectivas nunca entram na disputa.
            if prediction.get("origem") == "SIMULACAO_HISTORICA":
                continue

            game_id = str(prediction.get("idJogo") or prediction_doc.id)
            match = completed_by_id.get(game_id)
            if match is None:
                match = completed_by_key.get((
                    str(prediction.get("data") or "")[:10],
                    normalize(prediction.get("mandante")),
                    normalize(prediction.get("visitante")),
                ))
            if match is None:
                continue

            # O Profeta usa o palpite do motor que foi salvo antes do jogo.
            # Como o motor é igual para todos, basta um registro por partida.
            # Não recalculamos previsões depois de conhecer o resultado.
            if "placarCasa" in prediction and "placarFora" in prediction:
                ai_key = str(match.get("id") or match_key(match))
                if ai_key not in profeta_predictions:
                    try:
                        profeta_predictions[ai_key] = (
                            match,
                            int(prediction["placarCasa"]),
                            int(prediction["placarFora"]),
                        )
                    except (TypeError, ValueError):
                        pass

            # A pontuação individual continua usando exclusivamente o placar
            # digitado pelo próprio torcedor.
            if "usuarioPlacarCasa" not in prediction or "usuarioPlacarFora" not in prediction:
                continue

            try:
                predicted_home = int(prediction["usuarioPlacarCasa"])
                predicted_away = int(prediction["usuarioPlacarFora"])
            except (TypeError, ValueError):
                continue
            actual_home, actual_away = match["_score"]
            evaluated += 1
            evaluated_matches.add(str(match.get("id") or match_key(match)))

            exact_hit = predicted_home == actual_home and predicted_away == actual_away
            result_hit = outcome(predicted_home, predicted_away) == outcome(actual_home, actual_away)
            awarded = 3 if exact_hit else 1 if result_hit else 0
            if exact_hit:
                exact += 1
                correct += 1
                points += 3  # Placar exato vale 3 pontos no total.
            elif result_hit:
                correct += 1
                points += 1

            round_number = int(match.get("_round") or 0)
            if round_number > 0:
                round_stat = person_rounds["rounds"][round_number]
                round_stat["points"] += awarded
                round_stat["correct"] += int(exact_hit or result_hit)
                round_stat["exactScores"] += int(exact_hit)
                round_stat["evaluated"] += 1

        if evaluated:
            leaderboard.append({
                "nickname": nickname[:40],
                "points": points,
                "correct": correct,
                "exactScores": exact,
                "evaluated": evaluated,
            })

    # Participante oficial da IA: usa snapshots do próprio motor salvos
    # antes do início das partidas por usuários do app.
    ai_points = ai_exact = ai_correct = ai_evaluated = 0
    ai_id = hashlib.sha256(b"participant:brasileirao-o-profeta").hexdigest()[:16]
    ai_rounds = round_stats_by_person.setdefault(ai_id, {
        "nickname": "Brasileirão, o Profeta",
        "rounds": defaultdict(lambda: {"points": 0, "correct": 0, "exactScores": 0, "evaluated": 0}),
    })
    for match, predicted_home, predicted_away in profeta_predictions.values():
        actual_home, actual_away = match["_score"]
        ai_evaluated += 1
        evaluated_matches.add(str(match.get("id") or match_key(match)))

        exact_hit = predicted_home == actual_home and predicted_away == actual_away
        result_hit = outcome(predicted_home, predicted_away) == outcome(actual_home, actual_away)
        awarded = 3 if exact_hit else 1 if result_hit else 0
        if exact_hit:
            ai_exact += 1
            ai_correct += 1
            ai_points += 3
        elif result_hit:
            ai_correct += 1
            ai_points += 1

        round_number = int(match.get("_round") or 0)
        if round_number > 0:
            round_stat = ai_rounds["rounds"][round_number]
            round_stat["points"] += awarded
            round_stat["correct"] += int(exact_hit or result_hit)
            round_stat["exactScores"] += int(exact_hit)
            round_stat["evaluated"] += 1

    if ai_evaluated:
        leaderboard.append({
            "nickname": "Brasileirão, o Profeta",
            "points": ai_points,
            "correct": ai_correct,
            "exactScores": ai_exact,
            "evaluated": ai_evaluated,
        })

    leaderboard.sort(
        key=lambda item: (
            -item["points"],
            -item["exactScores"],
            -item["correct"],
            normalize(item["nickname"]),
        )
    )
    top10 = [
        {"position": index + 1, **entry}
        for index, entry in enumerate(leaderboard[:10])
    ]

    # Fotografia de cada rodada totalmente encerrada. Guarda a classificação
    # completa para que a evolução considere também quem estava fora do Top 10.
    completed_rounds = sorted(
        round_number for round_number, total in round_totals.items()
        if total > 0 and round_finished.get(round_number, 0) == total
    )
    cumulative: dict[str, dict[str, int]] = {}
    previous_positions: dict[str, int] = {}
    round_history = []
    latest_movement: dict[str, dict] = {}

    for round_number in completed_rounds:
        for participant_id, person in round_stats_by_person.items():
            stat = person["rounds"].get(round_number)
            if not stat:
                continue
            total_stat = cumulative.setdefault(participant_id, {
                "points": 0, "correct": 0, "exactScores": 0, "evaluated": 0,
            })
            for field in ("points", "correct", "exactScores", "evaluated"):
                total_stat[field] += stat[field]

        standings = []
        for participant_id, person in round_stats_by_person.items():
            totals = cumulative.get(participant_id)
            if not totals or totals["evaluated"] == 0:
                continue
            standings.append({
                "participantId": participant_id,
                "nickname": person["nickname"],
                **totals,
            })
        standings.sort(key=lambda item: (
            -item["points"], -item["exactScores"], -item["correct"],
            normalize(item["nickname"]),
        ))

        current_positions = {}
        for index, entry in enumerate(standings):
            position = index + 1
            participant_id = entry["participantId"]
            previous = previous_positions.get(participant_id)
            movement = 0 if previous is None else previous - position
            status = "NEW" if previous is None else (
                "UP" if movement > 0 else "DOWN" if movement < 0 else "SAME"
            )
            entry["position"] = position
            entry["movement"] = movement
            entry["movementStatus"] = status
            current_positions[participant_id] = position

        round_history.append({"round": round_number, "standings": standings})
        previous_positions = current_positions
        latest_movement = {
            entry["participantId"]: {
                "movement": entry["movement"],
                "movementStatus": entry["movementStatus"],
            }
            for entry in standings
        }

    # Aplica ao ranking atual a variação em relação à última rodada completa.
    current_id_by_nickname = {
        hashlib.sha256(
            (b"participant:brasileirao-o-profeta" if entry["nickname"] == "Brasileirão, o Profeta"
             else f"user:{uid}".encode("utf-8"))
        ).hexdigest()[:16]: entry
        for entry in []
    }
    for entry in top10:
        if entry["nickname"] == "Brasileirão, o Profeta":
            participant_id = ai_id
        else:
            # Resolve o ID estável sem expor UID no documento público.
            participant_id = next((
                hashlib.sha256(f"user:{user_doc.id}".encode("utf-8")).hexdigest()[:16]
                for user_doc in db.collection("users").stream()
                if str((user_doc.to_dict() or {}).get("name") or "").strip()[:40] == entry["nickname"]
            ), "")
        movement = latest_movement.get(participant_id)
        entry["movement"] = movement["movement"] if movement else 0
        entry["movementStatus"] = movement["movementStatus"] if movement else "NEW"

    # Documento único de leitura simples para o app. Não inclui UID, e-mail
    # nem histórico individual de palpites.
    db.collection("rankings").document(str(SEASON)).set({
        "season": SEASON,
        "updatedAt": firestore.SERVER_TIMESTAMP,
        "matchesProcessed": len(evaluated_matches),
        "participants": len(leaderboard),
        "top10": top10,
        "movementReferenceRound": completed_rounds[-1] if completed_rounds else 0,
        "roundHistory": round_history,
    })

    print(
        f"Ranking {SEASON} publicado: {len(top10)} posições, "
        f"{len(leaderboard)} participantes, {len(evaluated_matches)} partidas avaliadas."
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERRO AO ATUALIZAR RANKING: {exc}", file=sys.stderr)
        raise
