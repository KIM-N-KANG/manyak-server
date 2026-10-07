import base64
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("tokens", Path(__file__).parents[1] / "gen-tokens.py")
tokens = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tokens)


class TokenTest(unittest.TestCase):
    def test_seed_identity_and_hs256_wire_contract(self):
        key = b"test-only-secret" * 3
        public_id = tokens.public_id(500)
        self.assertEqual(public_id, "15960000-0000-4000-8000-000000000500")
        jwt = tokens.issue(key, public_id, "manyak", 1000, 14400)
        header, payload, signature = jwt.split(".")
        decode = lambda v: base64.urlsafe_b64decode(v + "=" * (-len(v) % 4))
        self.assertEqual(json.loads(decode(header)), {"alg": "HS256"})
        self.assertEqual(json.loads(decode(payload)), {"iss": "manyak", "sub": public_id, "iat": 1000, "exp": 15400})
        self.assertEqual(decode(signature), hmac.new(key, f"{header}.{payload}".encode(), hashlib.sha256).digest())


if __name__ == "__main__":
    unittest.main()
