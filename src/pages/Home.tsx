import React, { useEffect, useState } from 'react';
import { Typography, Row, Col, Card, Spin, Empty } from 'antd';
import { StarFilled, LoadingOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { api } from '../services/mockApi';
import { Movie } from '../types';

const { Title, Text } = Typography;

const Home: React.FC = () => {
  const [movies, setMovies] = useState<Movie[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const data = await api.getMovies();
        setMovies(data);
      } catch (error) {
        console.error("Failed to fetch movies", error);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, []);

  if (loading) {
    return (
      <div className="flex justify-center items-center h-[50vh]">
        <Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} />
      </div>
    );
  }

  return (
    <div className="space-y-8 animate-fade-in">
      <div className="flex justify-between items-end">
        <Title level={2} className="!mb-0 !text-white">热门推荐</Title>
        <Link to="/hot" className="text-blue-500 hover:text-blue-400">查看更多 &gt;</Link>
      </div>

      {movies.length === 0 ? (
        <Empty description="暂无影片数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <Row gutter={[24, 24]}>
          {movies.map((movie) => (
            <Col xs={12} sm={8} md={6} lg={4} key={movie.id}>
              <Link to={`/movie/${movie.id}`}>
                <Card
                  hoverable
                  cover={
                    <div className="relative aspect-[2/3] overflow-hidden group">
                      <img 
                        alt={movie.titleCn} 
                        src={movie.posterUrl} 
                        className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-110"
                      />
                      <div className="absolute top-2 right-2 bg-black/60 px-1.5 py-0.5 rounded text-yellow-400 font-bold flex items-center gap-1 text-xs">
                         <StarFilled /> {movie.ratingDouban}
                      </div>
                      <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black to-transparent p-3 pt-8">
                        <div className="text-white text-xs truncate opacity-90">{movie.year} / {movie.area[0]}</div>
                      </div>
                    </div>
                  }
                  bodyStyle={{ padding: '12px' }}
                  className="bg-[#1f1f1f] border-0"
                >
                  <Title level={5} className="!text-white !mb-1 truncate" title={movie.titleCn}>
                    {movie.titleCn}
                  </Title>
                  <Text className="text-gray-500 text-xs truncate block" title={movie.titleEn}>
                    {movie.titleEn}
                  </Text>
                </Card>
              </Link>
            </Col>
          ))}
        </Row>
      )}
    </div>
  );
};

export default Home;