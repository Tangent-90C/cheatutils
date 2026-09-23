import * as http from '/http.js'
import { components } from '../../components.js'

export function createComponent(template) {
    const args = {
        template,
        created() {
            http.get('/api/tacz-aim-assist').then(response => {
                this.config = response;
            });
            http.get('/api/entity-info').then(response => {
                const ids = new Set();
                response.forEach(entity => {
                    if (entity.id) {
                        ids.add(entity.id);
                    }
                });
                this.entityTypeIds = [...ids].sort();
            });
            http.get('/api/block-info').then(response => {
                const map = {};
                response.forEach(block => map[block.id] = block);
                this.blockInfo = map;
            });
        },
        data() {
            return {
                config: null,
                entityTypeIds: [],
                groupLabels: [
                    { id: 'group:monster', name: 'Mob (hostile)' },
                    { id: 'group:creature', name: 'Creature (animals)' },
                    { id: 'group:ambient', name: 'Ambient' },
                    { id: 'group:axolotls', name: 'Axolotls' },
                    { id: 'group:water_creature', name: 'Water Creature' },
                    { id: 'group:underground_water_creature', name: 'Underground Water Creature' },
                    { id: 'group:water_ambient', name: 'Water Ambient' },
                    { id: 'group:misc', name: 'Misc' }
                ],
                blockInfo: null,
                entityState: 'list',
                entitySearch: '',
                entityFiltered: [],
                blockState: 'list',
                blockSearch: '',
                blockFiltered: []
            };
        },
        methods: {
            openAddEntities() {
                this.entityState = 'add';
                this.entitySearch = '';
                this.filterEntities();
            },
            openAddGroups() {
                this.entityState = 'addGroup';
            },
            filterEntities() {
                const search = this.entitySearch.toLocaleLowerCase();
                this.entityFiltered = this.entityTypeIds.filter(id => {
                    return id.toLocaleLowerCase().indexOf(search) >= 0;
                });
            },
            addTargetEntity(id) {
                if (this.config.targetEntities.every(entry => entry.id != id)) {
                    this.config.targetEntities.push({ id: id, enabled: true });
                    this.update();
                }
                this.entityState = 'list';
            },
            removeTargetEntity(index) {
                this.config.targetEntities.splice(index, 1);
                this.update();
            },
            openAddBlocks() {
                this.blockState = 'add';
                this.blockSearch = '';
                this.filterBlocks();
            },
            filterBlocks() {
                if (this.blockInfo == null) {
                    this.blockFiltered = [];
                    return;
                }
                const search = this.blockSearch.toLocaleLowerCase();
                this.blockFiltered = Object.keys(this.blockInfo).filter(id => {
                    if (id.toLocaleLowerCase().indexOf(search) >= 0) {
                        return true;
                    }
                    const block = this.blockInfo[id];
                    return block.name != null && block.name.toLocaleLowerCase().indexOf(search) >= 0;
                });
            },
            addPenetrableBlock(id) {
                if (this.config.penetrableBlocks.every(entry => entry.block != id)) {
                    this.config.penetrableBlocks.push({ block: id, enabled: true });
                    this.update();
                }
                this.blockState = 'list';
            },
            removePenetrableBlock(index) {
                this.config.penetrableBlocks.splice(index, 1);
                this.update();
            },
            blockName(id) {
                return this.blockInfo != null && this.blockInfo[id] != null
                    && this.blockInfo[id].name != null ? this.blockInfo[id].name : id;
            },
            update() {
                return http.post('/api/tacz-aim-assist', this.config).then(response => {
                    this.config = response;
                });
            }
        }
    };
    components.add(args, 'SwitchCheckbox');
    components.add(args, 'Radio');
    components.add(args, 'CodeBlock');
    return args;
}
