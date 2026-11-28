# Card ids whose field $f is empty. Input: the cards array of board-check.yml.
.[]
| select(([.customFields[]? | select(.name == $f) | .value] | first) == null)
| .idReadable
