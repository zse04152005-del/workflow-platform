# Migration and release policy

Database migrations run as a dedicated Job before application rollout. The
release controller stops on checksum drift, failed migration, failed smoke
probe or error-budget breach. A compatibility window keeps the previous and
current application versions able to read the same event/schema contract.

Deployment uses immutable Git SHA image tags, canary replicas and a PDB. Rollback
is allowed only before an irreversible migration; after migration the release
must roll forward with a compatible corrective migration.
