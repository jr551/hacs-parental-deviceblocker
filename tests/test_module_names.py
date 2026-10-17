"""Every name an integration module uses must resolve.

The repo has no Home Assistant or aiohttp to load the API views with, so a view
that calls a helper which a refactor deleted fails only in production: 0.10.1
shipped ``PcPointsHourView`` calling ``_authorised`` after that helper was
replaced by ``_require_runtime``, which made every call to the points-hour
endpoint raise ``NameError``. This is the cheap static guard for that class of
bug.
"""

from __future__ import annotations

import ast
import builtins
import unittest
from pathlib import Path

INTEGRATION = (
    Path(__file__).resolve().parents[1] / "custom_components" / "rowe_pc_blocker"
)


def undefined_globals(path: Path) -> list[str]:
    """Names the module loads that nothing in it defines.

    Deliberately over-approximate: any assignment anywhere counts as a
    definition, so this reports module-level names that were never defined at
    all rather than every scoping mistake.
    """
    tree = ast.parse(path.read_text(encoding="utf-8"))
    defined = set(dir(builtins))
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            for alias in node.names:
                defined.add((alias.asname or alias.name).split(".")[0])
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            defined.add(node.name)
        elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            defined.add(node.id)
        elif isinstance(node, ast.arg):
            defined.add(node.arg)
        elif isinstance(node, ast.ExceptHandler) and node.name:
            defined.add(node.name)
        elif isinstance(node, (ast.Global, ast.Nonlocal)):
            defined.update(node.names)
    loaded = {
        node.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load)
    }
    return sorted(loaded - defined)


class ModuleNameTests(unittest.TestCase):
    def test_no_module_uses_a_name_it_never_defines(self) -> None:
        for path in sorted(INTEGRATION.glob("*.py")):
            with self.subTest(module=path.name):
                self.assertEqual(undefined_globals(path), [])


if __name__ == "__main__":
    unittest.main()
