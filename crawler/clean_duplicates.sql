-- Query to find duplicate records
SELECT movie_id, url, COUNT(*) as count
FROM resource_link
GROUP BY movie_id, url
HAVING count > 1;

-- Delete duplicate records (keep the one with the smallest ID, i.e., the earliest created)
DELETE t1 
FROM resource_link t1
INNER JOIN resource_link t2 
WHERE 
    t1.id > t2.id 
    AND t1.movie_id = t2.movie_id 
    AND t1.url = t2.url;

-- Verify keys after cleanup (Optional: Add Unique Constraint to prevent future duplicates at DB level)
-- ALTER TABLE resource_link ADD UNIQUE KEY `uk_movie_url` (`movie_id`, `url`(255));
