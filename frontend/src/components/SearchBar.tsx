import { useState, type FormEvent } from 'react';

interface SearchBarProps {
  onSearch: (query: string) => void;
  isLoading: boolean;
}

export function SearchBar({ onSearch, isLoading }: SearchBarProps) {
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!query.trim()) {
      setError('Please enter a search query');
      return;
    }
    setError('');
    onSearch(query.trim());
  };

  return (
    <form onSubmit={handleSubmit} style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', flexDirection: 'column' }}>
      <div style={{ display: 'flex', gap: '8px', width: '100%' }}>
        <input
          type="text"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setError(''); }}
          placeholder="Search employees, teams, job postings..."
          style={{ flex: 1, padding: '10px', fontSize: '16px', border: '1px solid #ccc', borderRadius: '4px' }}
          disabled={isLoading}
        />
        <button
          type="submit"
          disabled={isLoading}
          style={{ padding: '10px 20px', fontSize: '16px', backgroundColor: '#0073bb', color: 'white', border: 'none', borderRadius: '4px', cursor: isLoading ? 'not-allowed' : 'pointer' }}
        >
          {isLoading ? 'Searching...' : 'Search'}
        </button>
      </div>
      {error && <span style={{ color: 'red', fontSize: '14px' }}>{error}</span>}
    </form>
  );
}
