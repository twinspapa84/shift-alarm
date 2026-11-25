#!/usr/bin/env python3
"""교대 알람 스케줄 교차 검증.

앱의 핵심 로직(코틀린)이 만든 스케줄 CSV 를, 아래에 스펙만 보고 독립적으로 다시
구현한 파이썬 계산 결과와 날짜별로 대조한다. 두 구현이 다르면 실패(exit 1).

사용법:
    python3 tools/simulate_schedule.py [--csv app/build/test-output/schedule-kt.csv]
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
from datetime import date, timedelta

# ---------------------------------------------------------------- 스펙(요구사항)
ANCHOR_MONDAY = date(2026, 11, 23)   # 이 주가 주간
ANCHOR_IS_DAY = True
DAY_TIME = "05:45"
NIGHT_TIME = "14:30"


def monday_of(d: date) -> date:
    return d - timedelta(days=d.weekday())


def week_index(d: date) -> int:
    """기준 주에서 몇 주 떨어졌는지 (음수 가능)."""
    return (monday_of(d) - monday_of(ANCHOR_MONDAY)).days // 7


def week_type(d: date) -> str:
    """'주간' / '야간' (코틀린 로직과 같은 표기를 쓴다)."""
    same_as_anchor = week_index(d) % 2 == 0   # 파이썬 % 는 항상 0 이상
    is_day = ANCHOR_IS_DAY if same_as_anchor else not ANCHOR_IS_DAY
    return "주간" if is_day else "야간"


def is_weekday(d: date) -> bool:
    return d.weekday() < 5


def day_alarm_applies(d: date) -> bool:
    """주간 알람이 울려야 하는 날 (특근 스위치 꺼짐 기준)."""
    return is_weekday(d) and week_type(d) == "주간"


def night_alarm_applies(d: date) -> bool:
    return is_weekday(d) and week_type(d) == "야간"


def simulate(start: date, end: date, night_enabled: bool = True) -> list[dict]:
    """매일 22:00 자동 복구를 돌리며 '다음 날'의 알람 상태를 만든다."""
    rows: list[dict] = []
    day_on = True
    d = start
    while d <= end:
        tomorrow = d + timedelta(days=1)
        # 22:00 복구: 내일이 주간 주 평일이면 ON, 아니면 OFF
        day_on = day_alarm_applies(tomorrow)
        rows.append(
            {
                "date": tomorrow.isoformat(),
                "weekday": tomorrow.strftime("%A").upper(),
                "weekType": week_type(tomorrow),
                "dayOn": "true" if day_on else "false",
                "dayTime": DAY_TIME if day_on else "-",
                "dayKind": ("SATURDAY_OVERTIME" if tomorrow.weekday() == 5 else "DAY") if day_on else "-",
                "nightTime": NIGHT_TIME if (night_enabled and night_alarm_applies(tomorrow)) else "-",
            }
        )
        d += timedelta(days=1)
    return rows


def expected_facts() -> list[tuple[str, bool]]:
    """스펙에서 바로 읽어낼 수 있는 대표 사실들."""
    return [
        ("2026-11-23 월요일은 주간 주", week_type(date(2026, 11, 23)) == "주간"),
        ("2026-11-30 월요일은 야간 주", week_type(date(2026, 11, 30)) == "야간"),
        ("2026-12-07 월요일은 다시 주간 주", week_type(date(2026, 12, 7)) == "주간"),
        ("주간 주 월~금은 주간 알람", all(day_alarm_applies(date(2026, 11, 23) + timedelta(days=i)) for i in range(5))),
        ("주간 주 토요일은 알람 없음", not day_alarm_applies(date(2026, 11, 28))),
        ("야간 주 평일은 주간 알람 없음", not day_alarm_applies(date(2026, 11, 30))),
        ("야간 주 평일은 야간 알람", night_alarm_applies(date(2026, 11, 30))),
        ("야간 주 토요일은 야간 알람 없음", not night_alarm_applies(date(2026, 12, 5))),
    ]


def load_csv(path: str) -> list[dict]:
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--csv",
        default=os.path.join("app", "build", "test-output", "schedule-kt.csv"),
        help="코틀린 단위 테스트가 만든 스케줄 CSV",
    )
    args = parser.parse_args()

    ok = True
    print("== 스펙 사실 확인 ==")
    for label, passed in expected_facts():
        print(f"  [{'PASS' if passed else 'FAIL'}] {label}")
        ok = ok and passed

    start, end = date(2026, 11, 21), date(2026, 12, 20)
    py_rows = simulate(start, end)

    print("\n== 파이썬 독립 계산 결과(앞 10일) ==")
    for row in py_rows[:10]:
        print(f"  {row['date']} {row['weekday']:<9} {row['weekType']:<5} "
              f"주간={row['dayTime']:<5} 야간={row['nightTime']}")

    if not os.path.exists(args.csv):
        print(f"\n[SKIP] 코틀린 CSV 없음: {args.csv}")
        print("       (./gradlew testDebugUnitTest 실행 후 다시 시도)")
        return 0 if ok else 1

    kt_rows = load_csv(args.csv)
    print(f"\n== 코틀린 로직 vs 파이썬 독립 계산 ({len(py_rows)}일) ==")
    mismatches = 0
    by_date = {r["date"]: r for r in kt_rows}
    for py in py_rows:
        kt = by_date.get(py["date"])
        if kt is None:
            print(f"  [MISS] {py['date']} 코틀린 결과에 없음")
            mismatches += 1
            continue
        for key in ("weekType", "dayOn", "dayTime", "dayKind", "nightTime"):
            if str(kt[key]).strip() != str(py[key]).strip():
                print(f"  [DIFF] {py['date']} {key}: kotlin={kt[key]!r} python={py[key]!r}")
                mismatches += 1
    print(f"  불일치 {mismatches}건")
    ok = ok and mismatches == 0

    print("\n결과:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
