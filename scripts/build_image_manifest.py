#!/usr/bin/env python3
"""팀 제작 이미지 자산의 파일명을 파싱해 시드 매니페스트와 시드 마이그레이션 SQL을 굽는다.

전제(이 스크립트는 자산 원본이 필요하다):
  - 자산 디렉터리에 thumbnail/ · bg/ · character/ 하위 폴더와 PNG 파일

흐름: 파일명 파싱 → 장르 매핑 → imageKey 부여 → 매니페스트 JSON + 시드 SQL + S3 리네임 매핑.

리네임 매핑은 **원본(canonical) 객체 전용**이다. 썸네일의 축소 변형 `thumbnails/{imageKey}_sm.png`는
여기서 만들지 않는다 — 변형 생성·업로드는 인프라 소유이며 `manyak-terraform`이 담당한다(KNK-548).
서버는 `_sm` URL을 조합만 하므로(ImageUrlResolver), 이 파일만 보고 업로드하면 목록·채팅 카드용
축소본이 빠진다. 버킷을 재구성할 때는 반드시 `manyak-terraform`의 업로드 절차를 함께 밟아야 한다.

런타임 매칭의 정본은 DB 메타이며 파일명이 아니다(스펙 §4-3-9). 파일명은 이 도구의 입력일 뿐이고,
사람이 큐레이션한 장르 매핑을 거쳐야만 마스터 태그명이 된다 — 실물 파일명의 장르 표기(`재벌`·`헌터`)가
GENRE 마스터(`재벌물`·`헌터물`)와 달라 기계 파싱만으로는 매칭이 조용히 0건이 되기 때문이다.

사용:
    python3 scripts/build_image_manifest.py --assets-dir ~/Downloads/image
    python3 scripts/build_image_manifest.py --assets-dir ~/Downloads/image --check
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import unicodedata

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
MIGRATION_DIR = REPO_ROOT / "src/main/resources/db/migration"
MANIFEST_PATH = REPO_ROOT / "scripts/image-presets.manifest.json"
SEED_SQL_PATH = MIGRATION_DIR / "V46__seed_image_presets.sql"
RENAME_MAP_PATH = REPO_ROOT / "scripts/image-presets.rename.tsv"

# 디렉터리명 → (카탈로그 타입, imageKey 접두, S3 prefix)
TYPES = {
    "thumbnail": ("THUMBNAIL", "thumb", "thumbnails"),
    "bg": ("BACKGROUND", "bg", "backgrounds"),
    "character": ("CHARACTER", "char", "characters"),
}

# 파일명 장르 토큰 → GENRE 마스터 태그명. `판타지`는 조합 맥락으로 갈리므로 여기 두지 않는다.
GENRE_MAP = {
    "무협": "무협",
    "아포칼립스": "아포칼립스",
    "시스템": "시스템",
    "환생": "환생",
    "요리": "요리",
    "재벌": "재벌물",
    "헌터": "헌터물",
    "학원": "학원물",
    "육아": "육아물",
    "게임": "게임 판타지",
    "로맨스": "로맨스 판타지",
}


def resolve_genres(tokens: list[str]) -> list[str]:
    """파일명 장르 토큰 목록을 마스터 태그명 집합으로 옮긴다.

    `판타지` 단독 토큰은 마스터에 대응이 셋(현대·로맨스·게임 판타지)이라 동반 토큰으로 가른다.
    `판타지-로맨스`처럼 두 토큰이 같은 마스터 장르로 수렴하면 하나로 합쳐진다.
    """
    resolved = []
    for token in tokens:
        if token == "판타지":
            if "게임" in tokens:
                resolved.append("게임 판타지")
            elif "로맨스" in tokens:
                resolved.append("로맨스 판타지")
            else:
                resolved.append("현대 판타지")
        else:
            resolved.append(GENRE_MAP[token])
    return sorted(set(resolved))


def load_master_genres() -> set[str]:
    """마이그레이션에서 GENRE 마스터 태그명을 읽는다(정본은 DB가 아니라 마이그레이션)."""
    names: set[str] = set()
    for path in MIGRATION_DIR.glob("*.sql"):
        text = path.read_text(encoding="utf-8")
        names.update(
            unicodedata.normalize("NFC", m.group(1))
            for m in re.finditer(r"\('GENRE', '([^']+)'", text)
        )
    return names


def parse_assets(assets_dir: pathlib.Path) -> list[dict]:
    """자산 파일명을 파싱해 매니페스트 항목으로 만든다. 규칙 위반은 전부 모아 한 번에 실패시킨다."""
    entries: list[dict] = []
    errors: list[str] = []
    master = load_master_genres()

    for dir_name, (preset_type, key_prefix, s3_prefix) in TYPES.items():
        source_dir = assets_dir / dir_name
        if not source_dir.is_dir():
            errors.append(f"자산 디렉터리가 없습니다: {source_dir}")
            continue

        # macOS 파일명은 NFD(분해형)라 정규화 없이는 마스터 대조가 전부 조용히 실패한다.
        stems = sorted(
            unicodedata.normalize("NFC", path.stem) for path in source_dir.glob("*.png")
        )

        for index, stem in enumerate(stems, start=1):
            # thumbnail은 접두 뒤 언더스코어가 1개, bg·character는 2개다.
            body = re.sub(rf"^{dir_name}_+", "", stem)
            body = re.sub(r"__\d+$", "", body)  # 파일명 번호는 타입 안에서도 중복이라 버린다.
            fields = body.split("_")
            if len(fields) != 4:
                errors.append(f"{dir_name}/{stem}.png — 태그 축이 4개가 아닙니다: {fields}")
                continue

            genre_field, mood, subject, prop = fields
            # 하이픈은 장르 축에서만 복수 구분자다. 소품 축에서는 단어의 일부다(`철제-건틀릿`).
            tokens = genre_field.split("-")
            unknown = [t for t in tokens if t != "판타지" and t not in GENRE_MAP]
            if unknown:
                errors.append(f"{dir_name}/{stem}.png — 매핑 없는 장르 토큰: {unknown}")
                continue

            genres = resolve_genres(tokens)
            off_master = [g for g in genres if g not in master]
            if off_master:
                errors.append(f"{dir_name}/{stem}.png — 마스터에 없는 장르: {off_master}")
                continue

            image_key = f"{key_prefix}_{index:04d}"
            # 썸네일 축소 변형의 파생 객체 키가 {imageKey}_sm.png라, 원본 키가 _sm으로 끝나면 서로 충돌한다
            # (스펙 §4-3-9 매니페스트 검증, KNK-548). 현재 키 형식상 발생할 수 없지만 규칙을 여기서 잠근다.
            if image_key.endswith("_sm"):
                errors.append(f"{dir_name}/{stem}.png — imageKey가 _sm으로 끝납니다: {image_key}")
                continue

            entries.append(
                {
                    "imageKey": image_key,
                    "type": preset_type,
                    "genres": genres,
                    "mood": mood,
                    "subject": subject,
                    "prop": prop,
                    "sourceFile": f"{dir_name}/{stem}.png",
                    "objectKey": f"{s3_prefix}/{image_key}.png",
                }
            )

    if errors:
        print(f"자산 {len(errors)}건이 규칙을 어겼습니다. 시드를 만들지 않습니다:", file=sys.stderr)
        for message in errors:
            print(f"  - {message}", file=sys.stderr)
        sys.exit(1)

    return entries


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def render_seed_sql(entries: list[dict]) -> str:
    lines = [
        "-- KNK-409: 팀 제작 이미지 카탈로그 시드 (스펙 §4-3-9 자산 카탈로그).",
        "--",
        "-- 이 파일은 scripts/build_image_manifest.py가 생성한다. 직접 편집하지 않는다.",
        "-- 원본은 scripts/image-presets.manifest.json이며, 자산 파일명은 그 매니페스트의 입력일 뿐이다.",
        "--",
        "-- 장르는 스칼라 서브쿼리로 태그 마스터를 조회해 넣는다. 마스터에 없는 이름이면 NULL이 되어",
        "-- NOT NULL 위반으로 이 마이그레이션 전체가 롤백된다 — 규칙 위반 자산이 매칭에서 조용히 빠지는",
        "-- 것이 가장 위험한 실패이므로 시드 단계에서 실패시킨다.",
        "",
        "INSERT INTO image_presets (image_key, type, mood, subject, prop) VALUES",
    ]
    rows = [
        "    ({}, {}, {}, {}, {})".format(
            sql_literal(e["imageKey"]),
            sql_literal(e["type"]),
            sql_literal(e["mood"]),
            sql_literal(e["subject"]),
            sql_literal(e["prop"]),
        )
        for e in entries
    ]
    lines.append(",\n".join(rows) + ";")
    lines.append("")
    lines.append("INSERT INTO image_preset_genres (image_preset_id, tag_id) VALUES")

    genre_rows = []
    for entry in entries:
        for genre in entry["genres"]:
            genre_rows.append(
                "    ((SELECT id FROM image_presets WHERE image_key = {}),\n"
                "     (SELECT id FROM story_creation_tags\n"
                "       WHERE tag_type = 'GENRE' AND tag_source = 'PREDEFINED' AND name = {}))".format(
                    sql_literal(entry["imageKey"]), sql_literal(genre)
                )
            )
    lines.append(",\n".join(genre_rows) + ";")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-dir", required=True, type=pathlib.Path)
    parser.add_argument(
        "--check",
        action="store_true",
        help="파일을 쓰지 않고 파싱·검증만 한다(커밋된 산출물과의 표류 확인용).",
    )
    args = parser.parse_args()

    entries = parse_assets(args.assets_dir.expanduser())
    manifest = json.dumps(entries, ensure_ascii=False, indent=2) + "\n"
    seed_sql = render_seed_sql(entries)
    rename_map = "".join(f"{e['sourceFile']}\t{e['objectKey']}\n" for e in entries)

    by_type: dict[str, int] = {}
    for entry in entries:
        by_type[entry["type"]] = by_type.get(entry["type"], 0) + 1

    if args.check:
        drift = [
            path.name
            for path, expected in (
                (MANIFEST_PATH, manifest),
                (SEED_SQL_PATH, seed_sql),
                (RENAME_MAP_PATH, rename_map),
            )
            if not path.exists() or path.read_text(encoding="utf-8") != expected
        ]
        if drift:
            print(f"커밋된 산출물이 자산과 다릅니다: {', '.join(drift)}", file=sys.stderr)
            sys.exit(1)
        print(f"자산 {len(entries)}장, 산출물 최신 — {by_type}")
        return

    MANIFEST_PATH.write_text(manifest, encoding="utf-8")
    SEED_SQL_PATH.write_text(seed_sql, encoding="utf-8")
    RENAME_MAP_PATH.write_text(rename_map, encoding="utf-8")
    print(f"자산 {len(entries)}장 등재 — {by_type}")
    print(f"  매니페스트: {MANIFEST_PATH.relative_to(REPO_ROOT)}")
    print(f"  시드 SQL:   {SEED_SQL_PATH.relative_to(REPO_ROOT)}")
    print(f"  리네임 맵:  {RENAME_MAP_PATH.relative_to(REPO_ROOT)} (S3 업로드용 — 원본 전용)")
    print("  주의: 썸네일 축소 변형(_sm)은 이 맵에 없다. 생성·업로드는 manyak-terraform 소유(KNK-548).")


if __name__ == "__main__":
    main()
