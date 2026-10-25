@echo off
rem homelab: llm op Windows. Zet deze map (met llm en llm.cmd) in je PATH; vereist Python 3 (py).
py -3 "%~dp0llm" %*
