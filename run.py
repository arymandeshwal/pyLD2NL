import subprocess
import os

# command = "./owl2nl/owl2nl.sh -a ./src/test/resources/test_axioms.owl -u false -o ./src/test/resources/test_ontology.owl -t json -s test_out.json -m rule"

# subprocess.run(command, shell=True, check=True)

# command = "owl2nl/owl2nl.sh -a owl2nl/src/test/resources/test_axioms.owl -u false -o owl2nl/src/test/resources/test_ontology.owl -t json -s owl2nl/test_out.json -m rule"

# # Run the command
# result = subprocess.run(command, shell=True, stdout=subprocess.PIPE, text=True)

# # Print the output
# print(result.stdout)

os.chdir("owl2nl")
commands= ["./owl2nl.sh -a ./src/test/resources/test_axioms.owl -u false -o ./src/test/resources/test_ontology.owl -t json -s test_out.json -m rule"
        ]

# Run commands one by one
for command in commands:
    result = subprocess.run(command, shell=True, check=True, stdout=subprocess.PIPE, text=True)
    print(result.stdout)