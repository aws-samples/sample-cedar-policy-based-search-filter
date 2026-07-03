import { useState } from 'react';
import { SearchBar } from '../components/SearchBar';
import { UserInfo } from '../components/UserInfo';
import { ResultsPanel } from '../components/ResultsPanel';
import apiClient from '../services/apiClient';

interface SearchResponse {
  resultsByEntityType: Record<string, Array<Record<string, unknown>>>;
  totalResults: number;
  error: string | null;
}

export function SearchPage() {
  const [results, setResults] = useState<Record<string, Array<Record<string, unknown>>> | null>(null);
  const [totalResults, setTotalResults] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');
  const [hasSearched, setHasSearched] = useState(false);

  const handleSearch = async (query: string) => {
    setIsLoading(true);
    setError('');
    setHasSearched(true);

    try {
      const response = await apiClient.post<SearchResponse>('/search', { query });
      const data = response.data;

      if (data.error) {
        setError(data.error);
        setResults(null);
      } else {
        setResults(data.resultsByEntityType);
        setTotalResults(data.totalResults);
      }
    } catch (err) {
      setError('Search could not be completed. Please try again.');
      setResults(null);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div style={{ maxWidth: '900px', margin: '0 auto', padding: '20px' }}>
      <h1 style={{ marginBottom: '4px' }}>HR Portal Search</h1>
      <UserInfo />
      <SearchBar onSearch={handleSearch} isLoading={isLoading} />

      <div style={{ marginTop: '24px' }}>
        {isLoading && (
          <div style={{ textAlign: 'center', padding: '20px', color: '#666' }}>
            Searching...
          </div>
        )}

        {error && (
          <div style={{ padding: '12px', backgroundColor: '#fde8e8', color: '#cc0000', borderRadius: '4px' }}>
            {error}
          </div>
        )}

        {!isLoading && !error && hasSearched && totalResults === 0 && (
          <div style={{ textAlign: 'center', padding: '20px', color: '#666' }}>
            No results found.
          </div>
        )}

        {!isLoading && !error && results && totalResults > 0 && (
          <>
            <p style={{ color: '#666', marginBottom: '16px' }}>{totalResults} result(s) found</p>
            <ResultsPanel results={results} />
          </>
        )}
      </div>
    </div>
  );
}
