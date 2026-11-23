package com.catchnyang.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchPlayerRepository extends JpaRepository<MatchPlayerEntity, MatchPlayerEntity.Key> {

    List<MatchPlayerEntity> findByMatchId(Long matchId);
}
