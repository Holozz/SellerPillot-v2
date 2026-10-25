from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from llm import ModelError, chat


def main() -> int:
    messages = [{"role": "user", "content": "请只回复：连接成功。"}]
    try:
        message = chat(messages)
    except ModelError as exc:
        print(f"连接检查失败：{exc}")
        return 1

    print("已收到模型回复：")
    print(message["content"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
