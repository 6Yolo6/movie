import { MOCK_MOVIE, MOCK_RESOURCES, MOCK_USER } from '../constants';
import { Movie, ResourceLink, ResourceType, Provider, AuditStatus } from '../types';

const DB_KEYS = {
  MOVIES: 'gying_movies_v1',
  RESOURCES: 'gying_resources_v1',
  INIT: 'gying_db_initialized'
};

// Simulate network delay (300ms - 800ms)
const delay = (ms = 500) => new Promise(resolve => setTimeout(resolve, ms));

class MockApi {
  constructor() {
    this.init();
  }

  // Initialize DB with seed data if empty
  private init() {
    if (!localStorage.getItem(DB_KEYS.INIT)) {
      console.log('Initializing Mock DB...');
      
      // Generate some variation for the home page
      const seedMovies = Array(12).fill(MOCK_MOVIE).map((m, i) => ({
        ...m,
        id: `m${i + 1}`,
        titleCn: i === 0 ? m.titleCn : `${m.titleCn} ${i + 1}`,
        voteCount: m.voteCount + i * 123
      }));

      // Adjust mock resources to match the first movie
      const seedResources = MOCK_RESOURCES.map((r, i) => ({
        ...r,
        id: `r${i + 1}`,
        movieId: 'm1' // All initial resources belong to the first movie
      }));

      localStorage.setItem(DB_KEYS.MOVIES, JSON.stringify(seedMovies));
      localStorage.setItem(DB_KEYS.RESOURCES, JSON.stringify(seedResources));
      localStorage.setItem(DB_KEYS.INIT, 'true');
    }
  }

  // --- Movie Endpoints ---

  async getMovies(): Promise<Movie[]> {
    await delay();
    const data = localStorage.getItem(DB_KEYS.MOVIES);
    return data ? JSON.parse(data) : [];
  }

  async getMovieById(id: string): Promise<Movie | null> {
    await delay();
    const movies: Movie[] = JSON.parse(localStorage.getItem(DB_KEYS.MOVIES) || '[]');
    return movies.find(m => m.id === id) || null;
  }

  // --- Resource Endpoints ---

  async getResourcesByMovieId(movieId: string): Promise<ResourceLink[]> {
    await delay(300); // Faster than movie load
    const allResources: ResourceLink[] = JSON.parse(localStorage.getItem(DB_KEYS.RESOURCES) || '[]');
    // Sort by create time desc (mock logic, just reversing for now)
    return allResources.filter(r => r.movieId === movieId).reverse();
  }

  async addResource(resource: Omit<ResourceLink, 'id' | 'uploader' | 'auditStatus' | 'createTime'>): Promise<ResourceLink> {
    await delay(800); // Simulate processing
    const allResources: ResourceLink[] = JSON.parse(localStorage.getItem(DB_KEYS.RESOURCES) || '[]');
    
    const newResource: ResourceLink = {
      ...resource,
      id: `r${Date.now()}`,
      uploader: MOCK_USER,
      auditStatus: AuditStatus.PASSED, // Auto pass for demo
      createTime: '刚刚'
    };

    allResources.push(newResource);
    localStorage.setItem(DB_KEYS.RESOURCES, JSON.stringify(allResources));
    return newResource;
  }

  async deleteResource(resourceId: string): Promise<void> {
    await delay(400);
    let allResources: ResourceLink[] = JSON.parse(localStorage.getItem(DB_KEYS.RESOURCES) || '[]');
    allResources = allResources.filter(r => r.id !== resourceId);
    localStorage.setItem(DB_KEYS.RESOURCES, JSON.stringify(allResources));
  }
}

export const api = new MockApi();