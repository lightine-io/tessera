# Card ids whose description does not start with the Outcome block.
# Input: the cards array of board-check.yml.
.[]
| select((.description // "") | startswith("> **Outcome**") | not)
| .idReadable
