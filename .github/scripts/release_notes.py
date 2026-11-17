#!/usr/bin/env python3
"""Validate the sole update log and render release notes without publishing anything."""

import argparse
import re
from pathlib import Path

UPDATE_LOG = Path(__file__).resolve().parents[2] / "docs/releases/Update-Log.md"


def read_update_log(path=UPDATE_LOG):
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"更新日志必须是普通文件：{path}")
    content = path.read_text(encoding="utf-8").strip()
    visible = re.sub(r"<!--.*?-->", "", content, flags=re.DOTALL)
    body = []
    previous = ""
    for line in visible.splitlines():
        heading = re.match(r"^ {0,3}(#{1,6})(?:\s|$)", line)
        if heading:
            if len(heading.group(1)) == 1:
                raise ValueError("更新日志请使用二级及以下标题；版本标题由工作流生成")
        elif previous.strip() and re.fullmatch(r" {0,3}(?:=+|-+)\s*", line):
            raise ValueError("更新日志标题请使用 ## 等井号语法，不使用下划线标题")
        else:
            body.append(line)
        previous = line
    if not any(char.isalnum() for char in "\n".join(body)):
        raise ValueError("更新日志不能为空或仅包含标题、注释")
    if re.search(r"\b(?:TODO|TBD)\b|待补充|待填写", visible, flags=re.IGNORECASE):
        raise ValueError("更新日志包含未完成的占位内容")
    return content


def render_notes(content, *, mode, version, version_code, source_ref, source_sha,
                 repository, run_id):
    patterns = {"stable": r"[0-9]+\.[0-9]+\.[0-9]+",
                "prerelease": r"[0-9]+\.[0-9]+\.[0-9]+-rc\.[1-9][0-9]*"}
    if mode not in patterns or not re.fullmatch(patterns[mode], version):
        raise ValueError("发布模式与版本号格式不匹配")
    if version_code <= 0 or run_id <= 0:
        raise ValueError("versionCode 和构建记录编号必须为正整数")
    if not source_ref or re.search(r"[\r\n`]", source_ref):
        raise ValueError("来源引用为空或包含不支持的字符")
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("来源必须是完整 40 位 commit SHA")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("仓库必须为 owner/name")

    suffix = "（预发布）" if mode == "prerelease" else ""
    warning = "> 预发布版本，用于验证；不代表已完成设备回归。\n\n" if suffix else ""
    base_url = f"https://github.com/{repository}"
    return (f"# DiscordAutoTranslate {version}{suffix}\n\n"
            f"{warning}{content}\n\n"
            "## 构建信息\n\n"
            f"- 来源：`{source_ref}`（[{source_sha}]({base_url}/commit/{source_sha})）\n"
            f"- versionCode：{version_code}\n"
            f"- [构建记录]({base_url}/actions/runs/{run_id})\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="仅检查固定更新日志文件")
    parser.add_argument("--mode", choices=["stable", "prerelease"])
    parser.add_argument("--version")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--source-ref")
    parser.add_argument("--source-sha")
    parser.add_argument("--repository")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    metadata = {key: value for key, value in vars(args).items()
                if key not in {"check", "output"}}
    try:
        content = read_update_log()
        if args.check:
            if args.output is not None or any(value is not None for value in metadata.values()):
                parser.error("--check 不能与生成参数同时使用")
            print(f"更新日志检查通过：{UPDATE_LOG}")
            return
        if args.output is None or any(value is None for value in metadata.values()):
            parser.error("生成说明需要全部构建信息参数及 --output")
        notes = render_notes(content, **metadata)
        args.output.write_text(notes, encoding="utf-8")
        print(notes, end="")
    except (OSError, UnicodeError, ValueError) as error:
        parser.exit(1, f"更新日志错误：{error}\n")


if __name__ == "__main__":
    main()
