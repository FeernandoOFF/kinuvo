import sys
from pathlib import Path
if len(sys.argv) != 3:
    sys.exit("Usage: mise run init -- /absolute/data/home <port>")
home, port = sys.argv[1:]
if not Path(home).is_absolute() or "\n" in home:
    sys.exit("The data home must be an absolute path on one line")
if not port.isascii() or not port.isdecimal() or not 1 <= int(port) <= 65535:
    sys.exit("The port must be a number from 1 to 65535")
if any(Path(name).exists() for name in [".kinuvo-home", ".kinuvo-port"]):
    sys.exit("Checkout already configured; refusing to overwrite its pins")
created = []
try:
    for name, value in [(".kinuvo-home", home), (".kinuvo-port", str(int(port)))]:
        with open(name, "x") as file:
            created.append(Path(name))
            file.write(value + "\n")
except BaseException:
    for path in created:
        path.unlink()
    raise
print("Pinned home:", home)
print("Pinned port:", port)
