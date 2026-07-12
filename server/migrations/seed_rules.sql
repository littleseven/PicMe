-- 示例推荐规则（非个性化、纯规则）。字段按场景调整，改库即热更。
INSERT OR IGNORE INTO rule (scene, locale, params_json, version, enabled) VALUES
('night',    'zh', '{"ev":0.5,"beauty":40,"filter":"night"}',          1, 1),
('portrait', 'zh', '{"beauty":60,"slim":30,"filter":"none"}',           1, 1),
('food',     'zh', '{"saturation":1.2,"sharpness":1.1,"filter":"none"}', 1, 1),
('landscape','zh', '{"contrast":1.1,"saturation":1.1,"filter":"none"}',  1, 1),
('night',    'en', '{"ev":0.5,"beauty":40,"filter":"night"}',           1, 1),
('portrait', 'en', '{"beauty":60,"slim":30,"filter":"none"}',           1, 1);
