#!/bin/env python
import socket
import sys
from sys import argv
from os import getcwd

if len(argv) < 2:
    print "Usage: client <command>"
    sys.exit(-1)

try:
    f = file("%s/target/sbt-server-port" % getcwd(), "r")
    port = int(f.read())
    f.close()
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(("localhost", port))
    s.send(argv[1])
    s.shutdown(socket.SHUT_WR)
    r = s.recv(1024)
    s.close()
    sys.exit(int(r))
except Exception as e:
    print "sbt server not running in the current project: %s" % e
