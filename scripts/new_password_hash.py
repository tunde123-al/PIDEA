"""Print a BCrypt (cost 12) hash for an API user password.

Usage:  py -3.12 scripts\new_password_hash.py
The password is read with getpass so it never lands in shell history or process listings.
"""
import getpass

import bcrypt

pw = getpass.getpass("Password: ")
print(bcrypt.hashpw(pw.encode(), bcrypt.gensalt(rounds=12)).decode())
