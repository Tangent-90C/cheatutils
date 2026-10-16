import * as http from '/http.js'
import { components } from '../../components.js'

const soundInfoPromise = http.get('/api/sound-info');

export function createComponent(template) {
    const args = {
        template: template,
        created() {
            soundInfoPromise
                .then(ids => { this.allSounds = ids || []; })
                .catch(() => { this.allSounds = []; });
            this.loadSounds();
        },
        data() {
            return {
                state: 'list',
                config: null,
                allSounds: [],
                search: '',
                selectedId: null,
            };
        },
        computed: {
            sounds() {
                return this.config != null && this.config.sounds ? this.config.sounds : [];
            },
            // entries matching the filter box, for the add screen
            availableSounds() {
                let search = this.search.toLocaleLowerCase();
                if (search == '') {
                    return this.allSounds;
                }
                return this.allSounds.filter(id => id.toLocaleLowerCase().indexOf(search) >= 0);
            },
            selected() {
                if (this.selectedId == null) {
                    return null;
                }
                return this.sounds.find(e => e.id === this.selectedId) || null;
            }
        },
        methods: {
            loadSounds() {
                http.get('/api/sound-esp').then(response => {
                    this.config = response;
                    this.normalizeEntries();
                });
            },
            /** Entries saved before per-entry render settings existed get the box defaults. */
            normalizeEntries() {
                if (this.config == null || this.config.sounds == null) {
                    return;
                }
                for (const entry of this.config.sounds) {
                    // shape/offsetY stay null on purpose - null is how the server
                    // recognises a row saved before those settings existed
                    if (entry.drawMarker == null) { entry.drawMarker = true; }
                    if (entry.drawTracer == null) { entry.drawTracer = true; }
                    if (entry.throughWalls == null) { entry.throughWalls = true; }
                    if (entry.onlyNotVisible == null) { entry.onlyNotVisible = false; }
                }
            },
            update() {
                http.post('/api/sound-esp', this.config).then(response => {
                    this.config = response;
                });
            },
            backToList() {
                this.state = 'list';
                this.selectedId = null;
                this.search = '';
            },
            openAdd() {
                this.state = 'add';
                this.selectedId = null;
                this.search = '';
            },
            openEdit(id) {
                this.selectedId = id;
                this.state = 'edit';
            },
            /** Shape shown in the edit panel; a legacy row reads as Box until edited. */
            editShape: {
                get() {
                    if (this.selected == null) { return 0; }
                    return this.selected.shape == null ? 0 : this.selected.shape;
                },
                set(value) {
                    if (this.selected != null) { this.selected.shape = value; }
                }
            },
            /** Move-down shown in the edit panel; a legacy row reads as 0 until edited. */
            editOffset: {
                get() {
                    if (this.selected == null) { return 0; }
                    return this.selected.offsetY == null ? 0 : this.selected.offsetY;
                },
                set(value) {
                    if (this.selected != null) { this.selected.offsetY = value; }
                }
            },
            /** Adds one exact sound id as its own entry. */
            add(id) {
                if (id == null || id.length == 0) {
                    return;
                }
                if (this.config.sounds.some(e => e.id === id)) {
                    return;
                }
                this.config.sounds.push({
                    id: id, enabled: true, color: null, shape: 0, offsetY: 0,
                    drawMarker: true, drawTracer: true, throughWalls: true, onlyNotVisible: false,
                });
                this.selectedId = id;
                this.state = 'edit';
                this.update();
            },
            remove(id) {
                this.config.sounds = this.config.sounds.filter(e => e.id !== id);
                this.selectedId = null;
                this.state = 'list';
                this.update();
            },
            setColor(entry, color) {
                entry.color = color;
                this.update();
            },
            /** null = back to the default color of the sound category */
            clearColor(entry) {
                entry.color = null;
                this.update();
            },
            /** Adds a pattern. It is a visible row like any other, with its own settings. */
            addPattern() {
                let id = this.newId.trim();
                if (id.length == 0) {
                    return;
                }
                if (!id.endsWith('*')) {
                    id = id + '*';
                }
                this.add(id);
            },
            isWildcard(id) {
                return id.endsWith('*');
            }
        }
    };
    components.add(args, 'SwitchCheckbox');
    components.add(args, 'ColorPicker');
    components.add(args, 'ColorBox');
    return args;
}
