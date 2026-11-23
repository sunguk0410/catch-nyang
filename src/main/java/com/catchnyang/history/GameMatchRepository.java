package com.catchnyang.history;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GameMatchRepository extends JpaRepository<GameMatchEntity, Long> {
}
