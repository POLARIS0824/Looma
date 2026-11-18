# CJK substring search follow-up

The archive has several bilingual troubleshooting notes. Users often type a two-character compound taken from a procedure. They expect to find it inside a longer word. The n-gram index serves longer queries cheaply, with a separate path for inputs below its minimum gram size.

With a trigram index, route one- and two-character substring terms to an escaped LIKE predicate.

Unescaped wildcard characters are treated as user input; the persistence layer binds the predicate as a parameter.
