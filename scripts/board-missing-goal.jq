# Card ids in Options or Next that have a Service (product work) and no Goal.
# Input: the cards array of board-check.yml.
def field($n): [.customFields[]? | select(.name == $n) | .value] | first;
.[]
| select((field("Stage") | .name?) as $s | $s == "Options" or $s == "Next")
| select(field("Service") != null and field("Goal") == null)
| .idReadable
