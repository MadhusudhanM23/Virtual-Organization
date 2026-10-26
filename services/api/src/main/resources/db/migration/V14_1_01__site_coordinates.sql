-- Site location (WGS84 degrees) for local weather and map placement. Nullable: a site may have no known location.
alter table world.sites
    add column latitude  numeric(9, 6) check (latitude between -90 and 90),
    add column longitude numeric(9, 6) check (longitude between -180 and 180),
    add constraint sites_coordinates_paired check ((latitude is null) = (longitude is null));

-- Demo sites already seeded before this column existed (fresh databases get these from DemoSeeder).
update world.sites set latitude = 12.971600, longitude = 77.594600 where id = '018f0000-0000-7000-8000-00000000a021';
update world.sites set latitude = 19.076000, longitude = 72.877700 where id = '018f0000-0000-7000-8000-00000000a022';
update world.sites set latitude = 18.520400, longitude = 73.856700 where id = '018f0000-0000-7000-8000-00000000b021';
