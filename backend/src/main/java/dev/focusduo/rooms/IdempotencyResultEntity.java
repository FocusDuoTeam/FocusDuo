package dev.focusduo.rooms;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_results")
class IdempotencyResultEntity {
    @Id UUID id;
    @Column(nullable = false) UUID userId;
    @Column(nullable = false) UUID requestKey;
    @Column(nullable = false, length = 8) String method;
    @Column(nullable = false, length = 250) String path;
    Long originalRevision;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") JsonNode requestBody;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") JsonNode responseSnapshot;
    @Column(nullable = false) Instant createdAt;

    protected IdempotencyResultEntity() {}
}
