# AX Module Studio local coding fixture

This version-managed, non-secret file is the only path exposed by the local Tool Gateway.
The coding graph may read it through `read_file`; network, shell, Git mutation, arbitrary paths,
provider selection, and provider credentials remain outside Python authority.
