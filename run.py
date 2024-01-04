import subprocess
import os


os.chdir("owl2nl")
commands= ["./owl2nl.sh -a ./src/test/resources/test_axioms.owl -u false -o ./src/test/resources/test_ontology.owl -t json -s test_out.json -m rule"
        ]

# Run commands one by one
for command in commands:
    result = subprocess.run(command, shell=True, check=True, stdout=subprocess.PIPE, text=True)
    print(result.stdout)