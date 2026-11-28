# Card ids whose field $f is empty (no value, or an empty list for a multi-value field).
# Input: the cards array of board-check.yml.
.[]
| select(([.customFields[]? | select(.name == $f) | .value] | first) as $v | $v == null or $v == [])
| .idReadable
