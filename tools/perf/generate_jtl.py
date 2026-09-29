"""Generate deterministic JMeter CSV data without retaining rows."""

import argparse
from pathlib import Path


HEADER = b"timeStamp,elapsed,label,success\n"
MASK_64 = (1 << 64) - 1


def next_value(state: int) -> int:
    state ^= state >> 12
    state ^= (state << 25) & MASK_64
    state ^= state >> 27
    return state & MASK_64


def generate(rows: int, seed: int, output: Path) -> None:
    if rows < 0:
        raise ValueError("rows must be non-negative")
    if not 0 < seed <= MASK_64:
        raise ValueError("seed must be between 1 and 2^64-1")

    state = seed
    with output.open("wb") as stream:
        stream.write(HEADER)
        for index in range(rows):
            state = next_value(state)
            elapsed = 20 + (state % 180)
            if (index + seed) % 25 == 0:
                elapsed += 1000
            success = b"false" if (index + seed) % 20 == 0 else b"true"
            stream.write(
                f"{1704067200000 + index * 10},{elapsed},GET /api/orders,".encode("ascii")
                + success
                + b"\n"
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    generate(args.rows, args.seed, args.output)


if __name__ == "__main__":
    main()
